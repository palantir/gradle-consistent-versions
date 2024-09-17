/*
 * (c) Copyright 2024 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.gradle.versions.intellij;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;
import com.palantir.gradle.versions.intellij.psi.VersionPropsTypes;
import com.intellij.psi.TokenType;

%%

%class VersionPropsLexer
%implements FlexLexer
%unicode
%function advance
%type IElementType
%eof{  return;
%eof}

CRLF=\R
WHITE_SPACE=[\ \n\t\f]
FIRST_VALUE_CHARACTER=[^ \n\f\\] | "\\"{CRLF} | "\\".
VALUE_CHARACTER=[^\n\f\\] | "\\"{CRLF} | "\\".
END_OF_LINE_COMMENT=("#"|"!")[^\r\n]*
COLON=[:]
EQUALS=[=]
DOT=[.]
KEY_CHARACTER=[^:=\ \n\t\f\\] | "\\ "
GROUP_PART=[^.:=\ \n\t\f\\] | "\\ "

%state WAITING_NAME, WAITING_VERSION, WAITING_VALUE

%%

<YYINITIAL> {END_OF_LINE_COMMENT}                           { yybegin(YYINITIAL); return VersionPropsTypes.COMMENT; }

<YYINITIAL> {GROUP_PART}+                                   { yybegin(YYINITIAL); return VersionPropsTypes.GROUP_PART; }
<YYINITIAL> {DOT}                                           { yybegin(YYINITIAL); return VersionPropsTypes.DOT; }

<YYINITIAL> {COLON}                                         { yybegin(WAITING_NAME); return VersionPropsTypes.COLON; }

<WAITING_NAME> {KEY_CHARACTER}+                             { yybegin(WAITING_VERSION); return VersionPropsTypes.NAME_KEY; }

<WAITING_NAME> {WHITE_SPACE}+                               { yybegin(WAITING_NAME); return TokenType.WHITE_SPACE; }

<WAITING_NAME> {CRLF}({CRLF}|{WHITE_SPACE})+                { yybegin(YYINITIAL); return TokenType.WHITE_SPACE; }

<WAITING_VERSION> {EQUALS}                                  { yybegin(WAITING_VALUE); return VersionPropsTypes.EQUALS; }

<WAITING_VERSION> {WHITE_SPACE}+                            { yybegin(WAITING_VERSION); return TokenType.WHITE_SPACE; }

<WAITING_VERSION> {CRLF}({CRLF}|{WHITE_SPACE})+             { yybegin(YYINITIAL); return TokenType.WHITE_SPACE; }

<WAITING_VALUE> {WHITE_SPACE}+                              { yybegin(WAITING_VALUE); return TokenType.WHITE_SPACE; }

<WAITING_VALUE> {FIRST_VALUE_CHARACTER}{VALUE_CHARACTER}*   { yybegin(YYINITIAL); return VersionPropsTypes.VERSION; }

({CRLF}|{WHITE_SPACE})+                                     { yybegin(YYINITIAL); return TokenType.WHITE_SPACE; }

[^]                                                         { return TokenType.BAD_CHARACTER; }