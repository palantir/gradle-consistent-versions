// This is a generated file. Not intended for manual editing.
package com.palantir.gradle.versions.intellij.parser;

import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiBuilder.Marker;
import static com.palantir.gradle.versions.intellij.psi.VersionPropsTypes.*;
import static com.intellij.lang.parser.GeneratedParserUtilBase.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.lang.ASTNode;
import com.intellij.psi.tree.TokenSet;
import com.intellij.lang.PsiParser;
import com.intellij.lang.LightPsiParser;

@SuppressWarnings({"SimplifiableIfStatement", "UnusedAssignment"})
public class VersionPropsParser implements PsiParser, LightPsiParser {

  public ASTNode parse(IElementType t, PsiBuilder b) {
    parseLight(t, b);
    return b.getTreeBuilt();
  }

  public void parseLight(IElementType t, PsiBuilder b) {
    boolean r;
    b = adapt_builder_(t, b, this, null);
    Marker m = enter_section_(b, 0, _COLLAPSE_, null);
    r = parse_root_(t, b);
    exit_section_(b, 0, m, t, r, true, TRUE_CONDITION);
  }

  protected boolean parse_root_(IElementType t, PsiBuilder b) {
    return parse_root_(t, b, 0);
  }

  static boolean parse_root_(IElementType t, PsiBuilder b, int l) {
    return simpleFile(b, l + 1);
  }

  /* ********************************************************** */
  // GROUP_PART (DOT GROUP_PART)*
  public static boolean dependencyGroup(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "dependencyGroup")) return false;
    if (!nextTokenIs(b, GROUP_PART)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, GROUP_PART);
    r = r && dependencyGroup_1(b, l + 1);
    exit_section_(b, m, DEPENDENCY_GROUP, r);
    return r;
  }

  // (DOT GROUP_PART)*
  private static boolean dependencyGroup_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "dependencyGroup_1")) return false;
    while (true) {
      int c = current_position_(b);
      if (!dependencyGroup_1_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "dependencyGroup_1", c)) break;
    }
    return true;
  }

  // DOT GROUP_PART
  private static boolean dependencyGroup_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "dependencyGroup_1_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, DOT, GROUP_PART);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // NAME_KEY
  public static boolean dependencyName(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "dependencyName")) return false;
    if (!nextTokenIs(b, NAME_KEY)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, NAME_KEY);
    exit_section_(b, m, DEPENDENCY_NAME, r);
    return r;
  }

  /* ********************************************************** */
  // VERSION
  public static boolean dependencyVersion(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "dependencyVersion")) return false;
    if (!nextTokenIs(b, VERSION)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, VERSION);
    exit_section_(b, m, DEPENDENCY_VERSION, r);
    return r;
  }

  /* ********************************************************** */
  // property | COMMENT | CRLF
  static boolean item_(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "item_")) return false;
    boolean r;
    r = property(b, l + 1);
    if (!r) r = consumeToken(b, COMMENT);
    if (!r) r = consumeToken(b, CRLF);
    return r;
  }

  /* ********************************************************** */
  // dependencyGroup COLON dependencyName EQUALS dependencyVersion
  public static boolean property(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "property")) return false;
    if (!nextTokenIs(b, GROUP_PART)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = dependencyGroup(b, l + 1);
    r = r && consumeToken(b, COLON);
    r = r && dependencyName(b, l + 1);
    r = r && consumeToken(b, EQUALS);
    r = r && dependencyVersion(b, l + 1);
    exit_section_(b, m, PROPERTY, r);
    return r;
  }

  /* ********************************************************** */
  // item_*
  static boolean simpleFile(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "simpleFile")) return false;
    while (true) {
      int c = current_position_(b);
      if (!item_(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "simpleFile", c)) break;
    }
    return true;
  }

}
