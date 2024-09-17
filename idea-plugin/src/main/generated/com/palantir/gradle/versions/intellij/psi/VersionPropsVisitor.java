// This is a generated file. Not intended for manual editing.
package com.palantir.gradle.versions.intellij.psi;

import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiElement;

public class VersionPropsVisitor extends PsiElementVisitor {

  public void visitDependencyGroup(@NotNull VersionPropsDependencyGroup o) {
    visitPsiElement(o);
  }

  public void visitDependencyName(@NotNull VersionPropsDependencyName o) {
    visitPsiElement(o);
  }

  public void visitDependencyVersion(@NotNull VersionPropsDependencyVersion o) {
    visitPsiElement(o);
  }

  public void visitProperty(@NotNull VersionPropsProperty o) {
    visitPsiElement(o);
  }

  public void visitPsiElement(@NotNull PsiElement o) {
    visitElement(o);
  }

}
