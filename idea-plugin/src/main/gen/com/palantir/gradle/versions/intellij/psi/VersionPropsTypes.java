// This is a generated file. Not intended for manual editing.
package com.palantir.gradle.versions.intellij.psi;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.PsiElement;
import com.intellij.lang.ASTNode;
import com.palantir.gradle.versions.intellij.psi.impl.*;

public interface VersionPropsTypes {

  IElementType DEPENDENCY_GROUP = new VersionPropsElementType("DEPENDENCY_GROUP");
  IElementType DEPENDENCY_NAME = new VersionPropsElementType("DEPENDENCY_NAME");
  IElementType DEPENDENCY_VERSION = new VersionPropsElementType("DEPENDENCY_VERSION");
  IElementType PROPERTY = new VersionPropsElementType("PROPERTY");

  IElementType COLON = new VersionPropsTokenType("COLON");
  IElementType COMMENT = new VersionPropsTokenType("COMMENT");
  IElementType CRLF = new VersionPropsTokenType("CRLF");
  IElementType DOT = new VersionPropsTokenType("DOT");
  IElementType EQUALS = new VersionPropsTokenType("EQUALS");
  IElementType GROUP_PART = new VersionPropsTokenType("GROUP_PART");
  IElementType NAME_KEY = new VersionPropsTokenType("NAME_KEY");
  IElementType VERSION = new VersionPropsTokenType("VERSION");

  class Factory {
    public static PsiElement createElement(ASTNode node) {
      IElementType type = node.getElementType();
      if (type == DEPENDENCY_GROUP) {
        return new VersionPropsDependencyGroupImpl(node);
      }
      else if (type == DEPENDENCY_NAME) {
        return new VersionPropsDependencyNameImpl(node);
      }
      else if (type == DEPENDENCY_VERSION) {
        return new VersionPropsDependencyVersionImpl(node);
      }
      else if (type == PROPERTY) {
        return new VersionPropsPropertyImpl(node);
      }
      throw new AssertionError("Unknown element type: " + type);
    }
  }
}
