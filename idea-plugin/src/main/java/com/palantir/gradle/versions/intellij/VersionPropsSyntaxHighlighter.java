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

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.palantir.gradle.versions.intellij.psi.VersionPropsTypes;
import org.jetbrains.annotations.NotNull;

public class VersionPropsSyntaxHighlighter extends SyntaxHighlighterBase {

    public static final TextAttributesKey SEPARATOR =
            createTextAttributesKey("SIMPLE_SEPARATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);
    public static final TextAttributesKey DEPENDENCY_GROUP =
            createTextAttributesKey("SIMPLE_DEPENDENCY_GROUP", DefaultLanguageHighlighterColors.CLASS_NAME);
    public static final TextAttributesKey DEPENDENCY_NAME =
            createTextAttributesKey("SIMPLE_DEPENDENCY_NAME", DefaultLanguageHighlighterColors.STATIC_METHOD);
    public static final TextAttributesKey DEPENDENCY_VERSION =
            createTextAttributesKey("SIMPLE_DEPENDENCY_VERSION", DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey COMMENT =
            createTextAttributesKey("SIMPLE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey BAD_CHARACTER =
            createTextAttributesKey("SIMPLE_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER);

    private static final TextAttributesKey[] BAD_CHAR_KEYS = new TextAttributesKey[] {BAD_CHARACTER};
    private static final TextAttributesKey[] SEPARATOR_KEYS = new TextAttributesKey[] {SEPARATOR};
    private static final TextAttributesKey[] DEPENDENCY_GROUP_KEYS = new TextAttributesKey[] {DEPENDENCY_GROUP};
    private static final TextAttributesKey[] DEPENDENCY_NAME_KEYS = new TextAttributesKey[] {DEPENDENCY_NAME};
    private static final TextAttributesKey[] DEPENDENCY_VERSION_KEYS = new TextAttributesKey[] {DEPENDENCY_VERSION};
    private static final TextAttributesKey[] COMMENT_KEYS = new TextAttributesKey[] {COMMENT};
    private static final TextAttributesKey[] EMPTY_KEYS = new TextAttributesKey[0];

    @NotNull
    @Override
    public Lexer getHighlightingLexer() {
        return new VersionPropsLexerAdapter();
    }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
        if (tokenType.equals(VersionPropsTypes.COLON) || tokenType.equals(VersionPropsTypes.EQUALS)) {
            return SEPARATOR_KEYS;
        }
        if (tokenType.equals(VersionPropsTypes.GROUP_PART)) {
            return DEPENDENCY_GROUP_KEYS;
        }
        if (tokenType.equals(VersionPropsTypes.NAME_KEY)) {
            return DEPENDENCY_NAME_KEYS;
        }
        if (tokenType.equals(VersionPropsTypes.VERSION)) {
            return DEPENDENCY_VERSION_KEYS;
        }
        if (tokenType.equals(VersionPropsTypes.COMMENT)) {
            return COMMENT_KEYS;
        }
        if (tokenType.equals(TokenType.BAD_CHARACTER)) {
            return BAD_CHAR_KEYS;
        }
        return EMPTY_KEYS;
    }
}
