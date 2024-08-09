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