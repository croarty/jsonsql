package com.jsonsql.query;

import com.fasterxml.jackson.databind.node.NullNode;

/**
 * A null filled in for an earlier table's field on an unmatched RIGHT or FULL JOIN row. It
 * prints, compares and groups as an ordinary null. The one difference: a nested name such
 * as {@code profile.level} stops at it and reads null, where a null in the data lets the
 * name be looked for in a later table, as it always has.
 */
final class FilledNull extends NullNode {

    private static final long serialVersionUID = 1L;

    static final FilledNull INSTANCE = new FilledNull();

    private FilledNull() {
    }
}
