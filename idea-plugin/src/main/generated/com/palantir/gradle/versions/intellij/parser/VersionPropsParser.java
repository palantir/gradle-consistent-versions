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

  public ASTNode parse(IElementType root_, PsiBuilder builder_) {
    parseLight(root_, builder_);
    return builder_.getTreeBuilt();
  }

  public void parseLight(IElementType root_, PsiBuilder builder_) {
    boolean result_;
    builder_ = adapt_builder_(root_, builder_, this, null);
    Marker marker_ = enter_section_(builder_, 0, _COLLAPSE_, null);
    result_ = parse_root_(root_, builder_);
    exit_section_(builder_, 0, marker_, root_, result_, true, TRUE_CONDITION);
  }

  protected boolean parse_root_(IElementType root_, PsiBuilder builder_) {
    return parse_root_(root_, builder_, 0);
  }

  static boolean parse_root_(IElementType root_, PsiBuilder builder_, int level_) {
    return simpleFile(builder_, level_ + 1);
  }

  /* ********************************************************** */
  // GROUP_PART (DOT GROUP_PART)*
  public static boolean dependencyGroup(PsiBuilder builder_, int level_) {
    if (!recursion_guard_(builder_, level_, "dependencyGroup")) return false;
    if (!nextTokenIs(builder_, GROUP_PART)) return false;
    boolean result_;
    Marker marker_ = enter_section_(builder_);
    result_ = consumeToken(builder_, GROUP_PART);
    result_ = result_ && dependencyGroup_1(builder_, level_ + 1);
    exit_section_(builder_, marker_, DEPENDENCY_GROUP, result_);
    return result_;
  }

  // (DOT GROUP_PART)*
  private static boolean dependencyGroup_1(PsiBuilder builder_, int level_) {
    if (!recursion_guard_(builder_, level_, "dependencyGroup_1")) return false;
    while (true) {
      int pos_ = current_position_(builder_);
      if (!dependencyGroup_1_0(builder_, level_ + 1)) break;
      if (!empty_element_parsed_guard_(builder_, "dependencyGroup_1", pos_)) break;
    }
    return true;
  }

  // DOT GROUP_PART
  private static boolean dependencyGroup_1_0(PsiBuilder builder_, int level_) {
    if (!recursion_guard_(builder_, level_, "dependencyGroup_1_0")) return false;
    boolean result_;
    Marker marker_ = enter_section_(builder_);
    result_ = consumeTokens(builder_, 0, DOT, GROUP_PART);
    exit_section_(builder_, marker_, null, result_);
    return result_;
  }

  /* ********************************************************** */
  // NAME_KEY
  public static boolean dependencyName(PsiBuilder builder_, int level_) {
    if (!recursion_guard_(builder_, level_, "dependencyName")) return false;
    if (!nextTokenIs(builder_, NAME_KEY)) return false;
    boolean result_;
    Marker marker_ = enter_section_(builder_);
    result_ = consumeToken(builder_, NAME_KEY);
    exit_section_(builder_, marker_, DEPENDENCY_NAME, result_);
    return result_;
  }

  /* ********************************************************** */
  // VERSION
  public static boolean dependencyVersion(PsiBuilder builder_, int level_) {
    if (!recursion_guard_(builder_, level_, "dependencyVersion")) return false;
    if (!nextTokenIs(builder_, VERSION)) return false;
    boolean result_;
    Marker marker_ = enter_section_(builder_);
    result_ = consumeToken(builder_, VERSION);
    exit_section_(builder_, marker_, DEPENDENCY_VERSION, result_);
    return result_;
  }

  /* ********************************************************** */
  // property | COMMENT | CRLF
  static boolean item_(PsiBuilder builder_, int level_) {
    if (!recursion_guard_(builder_, level_, "item_")) return false;
    boolean result_;
    result_ = property(builder_, level_ + 1);
    if (!result_) result_ = consumeToken(builder_, COMMENT);
    if (!result_) result_ = consumeToken(builder_, CRLF);
    return result_;
  }

  /* ********************************************************** */
  // dependencyGroup COLON dependencyName EQUALS dependencyVersion
  public static boolean property(PsiBuilder builder_, int level_) {
    if (!recursion_guard_(builder_, level_, "property")) return false;
    if (!nextTokenIs(builder_, GROUP_PART)) return false;
    boolean result_;
    Marker marker_ = enter_section_(builder_);
    result_ = dependencyGroup(builder_, level_ + 1);
    result_ = result_ && consumeToken(builder_, COLON);
    result_ = result_ && dependencyName(builder_, level_ + 1);
    result_ = result_ && consumeToken(builder_, EQUALS);
    result_ = result_ && dependencyVersion(builder_, level_ + 1);
    exit_section_(builder_, marker_, PROPERTY, result_);
    return result_;
  }

  /* ********************************************************** */
  // item_*
  static boolean simpleFile(PsiBuilder builder_, int level_) {
    if (!recursion_guard_(builder_, level_, "simpleFile")) return false;
    while (true) {
      int pos_ = current_position_(builder_);
      if (!item_(builder_, level_ + 1)) break;
      if (!empty_element_parsed_guard_(builder_, "simpleFile", pos_)) break;
    }
    return true;
  }

}
