/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
package com.tom.rv2ide.language.services.java.providers;

import androidx.annotation.NonNull;
import com.tom.rv2ide.language.services.java.interop.JavaPsiHoverTarget;
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop;
import com.tom.rv2ide.lsp.models.DefinitionParams;
import com.tom.rv2ide.lsp.models.MarkupContent;
import com.tom.rv2ide.progress.ICancelChecker;

public final class JavaHoverProvider extends CancelableServiceProvider {
  public JavaHoverProvider(ICancelChecker cancelChecker) {
    super(cancelChecker);
  }

  @NonNull
  public MarkupContent hover(@NonNull DefinitionParams params) {
    abortIfCancelled();
    final JavaPsiHoverTarget target = JavaPsiServiceInterop.hover(params);
    if (target == null) {
      return new MarkupContent();
    }
    final MarkupContent content = target.kind == JavaPsiHoverTarget.Kind.KOTLIN_SOURCE
        ? KotlinSourceHover.find(params)
        : target.content;
    abortIfCancelled();
    return content == null ? new MarkupContent() : content;
  }
}
