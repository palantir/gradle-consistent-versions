// This is a generated file. Not intended for manual editing.
package com.palantir.gradle.versions.intellij.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface VersionPropsProperty extends PsiElement {

  @NotNull
  VersionPropsDependencyGroup getDependencyGroup();

  @NotNull
  VersionPropsDependencyName getDependencyName();

  @NotNull
  VersionPropsDependencyVersion getDependencyVersion();

}
