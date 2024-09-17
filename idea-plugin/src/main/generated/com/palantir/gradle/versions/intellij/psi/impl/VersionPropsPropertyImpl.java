// This is a generated file. Not intended for manual editing.
package com.palantir.gradle.versions.intellij.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static com.palantir.gradle.versions.intellij.psi.VersionPropsTypes.*;
import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.palantir.gradle.versions.intellij.psi.*;

public class VersionPropsPropertyImpl extends ASTWrapperPsiElement implements VersionPropsProperty {

  public VersionPropsPropertyImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(@NotNull VersionPropsVisitor visitor) {
    visitor.visitProperty(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof VersionPropsVisitor) accept((VersionPropsVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @NotNull
  public VersionPropsDependencyGroup getDependencyGroup() {
    return findNotNullChildByClass(VersionPropsDependencyGroup.class);
  }

  @Override
  @NotNull
  public VersionPropsDependencyName getDependencyName() {
    return findNotNullChildByClass(VersionPropsDependencyName.class);
  }

  @Override
  @NotNull
  public VersionPropsDependencyVersion getDependencyVersion() {
    return findNotNullChildByClass(VersionPropsDependencyVersion.class);
  }

}
