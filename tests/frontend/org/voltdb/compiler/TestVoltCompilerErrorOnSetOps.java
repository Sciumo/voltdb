/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.compiler;


public class TestVoltCompilerErrorOnSetOps extends VoltCompilerErrorTest {

    public void testNoErrorOnParens() throws Exception {
        statementNonErrorTest("PARENS", "(select ival from blah);");
    }
    public void testErrorOnUnion() throws Exception {
        statementErrorTest("UNION", "(select ival from blah) union (select ival from indexed_blah);");
        statementErrorTest("UNION", "select ival from blah union select ival from indexed_blah;");
    }

    public void testErrorOnIntersect() throws Exception {
        statementErrorTest("INTERSECT", "(select ival from blah) intersect (select ival from indexed_blah);");
        statementErrorTest("INTERSECT", "select ival from blah intersect select ival from indexed_blah;");
    }

    public void testErrorOnExcept() throws Exception {
        statementErrorTest("EXCEPT", "(select ival from blah) except (select ival from indexed_blah);");
        statementErrorTest("EXCEPT", "select ival from blah except select ival from indexed_blah;");
    }
}
