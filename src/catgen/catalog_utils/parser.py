#!/usr/bin/env python

# This file is part of VoltDB.
# Copyright (C) 2008-2012 VoltDB Inc.
#
# VoltDB is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# VoltDB is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.

from re import compile, DOTALL, MULTILINE
from testdata import testspec

strip_comments_regex = compile( r'/\*.*?\*/|//.*?$', DOTALL | MULTILINE )

def strip_comments(text):
    """
    Removes all the // and /**/ comments.
    """
    return strip_comments_regex.sub( '', text )

class Field:
    def __init__(self, name, type, comment):
        self.name = name
        self.type = type
        self.comment = comment

    def has_comment(self):
        return self.comment != None and len(self.comment) > 0

class CatalogDefn:
    def __init__(self, name, fields, comment):
        self.name = name
        self.fields = fields
        self.comment = comment

    def has_comment(self):
        return self.comment != None and len(self.comment) > 0

# return values are lists of CatalogDefn
def parse(text):
    retval = []

    text = strip_comments(text)
    text = text.split('\n')

    while len(text):
        line = text.pop(0).split(None, 2)
        if len(line) == 0:
            continue
        if line.pop(0) != "begin":
            raise Exception("Didn't find expected \"begin\" token.")
        name = line.pop(0)
        comment = None
        if len(line):
            comment = line.pop(0).strip("\"")

        fields = []
        fieldline = text.pop(0).split(None, 2)
        while fieldline[0] != "end":
            typetoken = fieldline.pop(0)
            nametoken = fieldline.pop(0)
            fieldcomment = None
            if len(fieldline):
                fieldcomment = fieldline.pop(0).strip("\"")
            fields.append(Field(nametoken, typetoken, fieldcomment))
            fieldline = text.pop(0).split(None, 2)
        retval.append(CatalogDefn(name, fields, comment))

    return retval

#classes = parse(testspec)
