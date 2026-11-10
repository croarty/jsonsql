package com.jsonsql.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jsonsql.config.MappingManager;
import com.jsonsql.query.ParsedQuery;
import com.jsonsql.query.TableFileResolver;
import com.jsonsql.query.TableInfo;
import com.jsonsql.query.UnnestInfo;
import com.jsonsql.query.Values;
import com.jsonsql.view.MaterializedViewBuilder;
import com.jsonsql.view.MaterializedViewManager;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which backing files of the FROM table can be skipped for a query, using the
 * declared indexes. Pruning is correctness-preserving: a file is skipped only when a
 * covered, fresh summary proves no row in it can match an AND-mandatory predicate.
 *
 * <p>Any uncertainty (no index, OR predicate, missing/stale summary, mixed types,
 * parse trouble) results in the file being kept — i.e. a full scan.
 */
public class IndexPlanner {
    private enum Op { EQ, IN, RANGE }

    private static final class Predicate {
        Op op;
        String fieldRaw;      // the raw WHERE column reference (e.g. "p.category", "review.rating")
        String rangeOp;       // ">", ">=", "<", "<=" when op == RANGE
        JsonNode value;         // for EQ / RANGE
        List<JsonNode> values;  // for IN
        TableIndex index;
    }

    private final IndexManager indexManager;
    private final IndexStore indexStore;
    private final MappingManager mappingManager;
    private final TableFileResolver resolver;
    private final MaterializedViewManager viewManager;

    public IndexPlanner(IndexManager indexManager, IndexStore indexStore,
                        MappingManager mappingManager, TableFileResolver resolver) {
        this(indexManager, indexStore, mappingManager, resolver, null);
    }

    public IndexPlanner(IndexManager indexManager, IndexStore indexStore,
                        MappingManager mappingManager, TableFileResolver resolver,
                        MaterializedViewManager viewManager) {
        this.indexManager = indexManager;
        this.indexStore = indexStore;
        this.mappingManager = mappingManager;
        this.resolver = resolver;
        this.viewManager = viewManager;
    }

    /**
     * Return the subset of {@code currentFiles} that must be loaded. Falls back to the full
     * list on any uncertainty or error.
     */
    public List<File> prune(TableInfo fromTable, ParsedQuery query, List<File> currentFiles) {
        try {
            return doPrune(fromTable, query, currentFiles);
        } catch (RuntimeException e) {
            return currentFiles;
        }
    }

    private List<File> doPrune(TableInfo fromTable, ParsedQuery query, List<File> currentFiles) {
        String table = fromTable.getTableName();
        if (table == null || !indexManager.hasIndexesForTable(table) || !query.hasWhere()) {
            return currentFiles;
        }

        List<Expression> conjuncts = new ArrayList<>();
        collectConjuncts(query.getWhereExpression(), conjuncts);

        String alias = fromTable.getEffectiveName();
        String mappingJsonPath = isMaterializedView(table)
            ? MaterializedViewBuilder.INDEX_JSON_PATH_MARKER
            : mappingManager.getJsonPathOnly(table);

        // Map UNNEST element column -> the (alias-stripped) source array path.
        Map<String, String> unnestSource = new HashMap<>();
        if (query.hasUnnests()) {
            for (UnnestInfo u : query.getUnnests()) {
                unnestSource.put(u.getElementColumn(), stripAlias(u.getArrayExpression(), alias));
            }
        }

        List<Predicate> usable = new ArrayList<>();
        for (Expression conjunct : conjuncts) {
            Predicate pred = extractPredicate(conjunct);
            if (pred == null) {
                continue;
            }
            String indexedField = resolveIndexedField(pred.fieldRaw, alias, unnestSource);
            if (indexedField == null || !indexManager.hasIndex(table, indexedField)) {
                continue;
            }
            TableIndex idx = indexStore.load(table, indexedField);
            if (idx == null) {
                continue;
            }
            // Mapping/JSONPath changed since build -> index is stale, do not use.
            if (idx.getJsonPath() != null && mappingJsonPath != null
                && !idx.getJsonPath().equals(mappingJsonPath)) {
                continue;
            }
            pred.index = idx;
            usable.add(pred);
        }

        if (usable.isEmpty()) {
            return currentFiles;
        }

        File base = isMaterializedView(table) ? new File(table) : resolver.resolveBase(table);
        List<File> keep = new ArrayList<>();
        for (File file : currentFiles) {
            boolean prunable = false;
            for (Predicate pred : usable) {
                FileSummary summary = summaryFor(pred.index, file, base);
                if (summary == null || !fingerprintMatches(summary, file)) {
                    continue; // not covered or stale -> cannot prove anything
                }
                if (!canMatch(summary, pred)) {
                    prunable = true;
                    break;
                }
            }
            if (!prunable) {
                keep.add(file);
            }
        }
        return keep;
    }

    // -- predicate extraction -------------------------------------------------

    /** Split on top-level AND only; everything else becomes an opaque (likely unusable) conjunct. */
    private void collectConjuncts(Expression expr, List<Expression> out) {
        Expression e = unwrap(expr);
        if (e instanceof AndExpression) {
            AndExpression and = (AndExpression) e;
            collectConjuncts(and.getLeftExpression(), out);
            collectConjuncts(and.getRightExpression(), out);
        } else if (e instanceof Between && !((Between) e).isNot()) {
            // x BETWEEN a AND b is the range x >= a AND x <= b
            Between between = (Between) e;
            GreaterThanEquals atLeast = new GreaterThanEquals();
            atLeast.setLeftExpression(between.getLeftExpression());
            atLeast.setRightExpression(between.getBetweenExpressionStart());
            MinorThanEquals atMost = new MinorThanEquals();
            atMost.setLeftExpression(between.getLeftExpression());
            atMost.setRightExpression(between.getBetweenExpressionEnd());
            out.add(atLeast);
            out.add(atMost);
        } else {
            out.add(e);
        }
    }

    private Expression unwrap(Expression e) {
        while (e instanceof Parenthesis) {
            e = ((Parenthesis) e).getExpression();
        }
        return e;
    }

    private Predicate extractPredicate(Expression conjunct) {
        Expression e = unwrap(conjunct);
        if (e instanceof EqualsTo) {
            return comparison((ComparisonOperator) e, Op.EQ, "=");
        }
        if (e instanceof GreaterThan) {
            return comparison((ComparisonOperator) e, Op.RANGE, ">");
        }
        if (e instanceof GreaterThanEquals) {
            return comparison((ComparisonOperator) e, Op.RANGE, ">=");
        }
        if (e instanceof MinorThan) {
            return comparison((ComparisonOperator) e, Op.RANGE, "<");
        }
        if (e instanceof MinorThanEquals) {
            return comparison((ComparisonOperator) e, Op.RANGE, "<=");
        }
        if (e instanceof InExpression) {
            return inPredicate((InExpression) e);
        }
        return null;
    }

    private Predicate comparison(ComparisonOperator op, Op kind, String symbol) {
        Expression left = op.getLeftExpression();
        Expression right = op.getRightExpression();

        String column = columnName(left);
        JsonNode literal = literal(right);
        boolean reversed = false;
        if (column == null || literal == null) {
            column = columnName(right);
            literal = literal(left);
            reversed = true;
        }
        if (column == null || literal == null) {
            return null;
        }

        Predicate p = new Predicate();
        p.fieldRaw = column;
        p.value = literal;
        if (kind == Op.EQ) {
            p.op = Op.EQ;
        } else {
            p.op = Op.RANGE;
            p.rangeOp = reversed ? flip(symbol) : symbol;
        }
        return p;
    }

    private Predicate inPredicate(InExpression in) {
        if (in.isNot()) {
            return null;
        }
        String column = columnName(in.getLeftExpression());
        if (column == null) {
            return null;
        }
        Expression right = in.getRightExpression();
        if (!(right instanceof ParenthesedExpressionList)) {
            return null;
        }
        List<JsonNode> values = new ArrayList<>();
        for (Object o : (ParenthesedExpressionList<?>) right) {
            JsonNode v = literal((Expression) o);
            if (v == null) {
                return null;
            }
            values.add(v);
        }
        if (values.isEmpty()) {
            return null;
        }
        Predicate p = new Predicate();
        p.op = Op.IN;
        p.fieldRaw = column;
        p.values = values;
        return p;
    }

    private String flip(String op) {
        switch (op) {
            case ">": return "<";
            case ">=": return "<=";
            case "<": return ">";
            case "<=": return ">=";
            default: return op;
        }
    }

    private String columnName(Expression e) {
        return (e instanceof Column) ? e.toString() : null;
    }

    /** The literal as the value WHERE compares with, or null if it isn't a plain literal. */
    private JsonNode literal(Expression e) {
        if (e instanceof StringValue) {
            return TextNode.valueOf(((StringValue) e).getNotExcapedValue());
        }
        if (e instanceof LongValue) {
            return BigIntegerNode.valueOf(((LongValue) e).getBigIntegerValue());
        }
        if (e instanceof DoubleValue) {
            return DoubleNode.valueOf(((DoubleValue) e).getValue());
        }
        return null;
    }

    /**
     * Resolve a WHERE field reference to the indexed field name, accounting for the FROM
     * alias and UNNEST element columns. Returns null if it can't be mapped.
     */
    private String resolveIndexedField(String fieldPath, String alias, Map<String, String> unnestSource) {
        int dot = fieldPath.indexOf('.');
        String first = dot >= 0 ? fieldPath.substring(0, dot) : fieldPath;
        String rest = dot >= 0 ? fieldPath.substring(dot + 1) : null;

        if (unnestSource.containsKey(first)) {
            String src = unnestSource.get(first);
            return rest == null ? src : src + "." + rest;
        }

        if (alias != null && fieldPath.startsWith(alias + ".")) {
            return fieldPath.substring(alias.length() + 1);
        }
        return fieldPath;
    }

    private String stripAlias(String path, String alias) {
        if (path == null) {
            return null;
        }
        if (alias != null && path.startsWith(alias + ".")) {
            return path.substring(alias.length() + 1);
        }
        return path;
    }

    // -- per-file matching ----------------------------------------------------

    private FileSummary summaryFor(TableIndex index, File file, File base) {
        String canon = TableFileResolver.canonicalPath(file);
        for (FileSummary s : index.getFiles()) {
            if (canon.equals(s.getCanonicalPath())) {
                return s;
            }
        }
        String rel = TableFileResolver.relativePath(base, file);
        for (FileSummary s : index.getFiles()) {
            if (rel.equals(s.getRelPath())) {
                return s;
            }
        }
        return null;
    }

    private boolean fingerprintMatches(FileSummary summary, File file) {
        return summary.getMtime() == file.lastModified() && summary.getSize() == file.length();
    }

    private boolean canMatch(FileSummary summary, Predicate pred) {
        // No value for this field anywhere in the file -> nothing can match (=, IN, range).
        if ("empty".equals(summary.getValueType())) {
            return false;
        }
        switch (pred.op) {
            case EQ:
                return valueCanEqual(summary, pred.value);
            case IN:
                for (JsonNode v : pred.values) {
                    if (valueCanEqual(summary, v)) {
                        return true;
                    }
                }
                return false;
            case RANGE:
                return rangeCanMatch(summary, pred.rangeOp, pred.value);
            default:
                return true;
        }
    }

    private boolean valueCanEqual(FileSummary summary, JsonNode value) {
        if (!summary.isValuesOmitted() && summary.getDistinctValues() != null) {
            for (JsonNode v : summary.getDistinctValues()) {
                if (Values.equal(v, value)) {
                    return true;
                }
            }
            return false;
        }
        // High cardinality: can only reject out-of-range equality via min/max.
        return withinMinMax(summary, value);
    }

    // min and max are only stored when every value in the file is a number, or every value
    // is text. Values.compare orders a literal of the other kind entirely before or after
    // them, which is also how WHERE treats it, so no type check is needed here.

    private boolean withinMinMax(FileSummary summary, JsonNode value) {
        JsonNode min = summary.getMin();
        JsonNode max = summary.getMax();
        if (min == null || max == null) {
            return true;
        }
        return Values.compare(value, min) >= 0 && Values.compare(value, max) <= 0;
    }

    private boolean rangeCanMatch(FileSummary summary, String op, JsonNode value) {
        JsonNode min = summary.getMin();
        JsonNode max = summary.getMax();
        if (min == null || max == null) {
            return true; // cannot prune (e.g. mixed/boolean types have no min/max)
        }
        switch (op) {
            case ">":  return Values.compare(max, value) > 0;
            case ">=": return Values.compare(max, value) >= 0;
            case "<":  return Values.compare(min, value) < 0;
            case "<=": return Values.compare(min, value) <= 0;
            default:   return true;
        }
    }

    private boolean isMaterializedView(String table) {
        return viewManager != null && viewManager.hasView(table) && !mappingManager.hasMapping(table);
    }
}
