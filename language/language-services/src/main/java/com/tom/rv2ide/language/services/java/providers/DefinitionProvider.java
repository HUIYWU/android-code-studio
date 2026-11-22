/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.language.services.java.providers;

import androidx.annotation.NonNull;
import com.tom.rv2ide.language.services.java.interop.JavaPsiDefinitionTarget;
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop;
import com.tom.rv2ide.language.services.java.providers.definition.KotlinSourceNavigation;
import com.tom.rv2ide.lsp.models.DefinitionParams;
import com.tom.rv2ide.lsp.models.DefinitionResult;
import com.tom.rv2ide.models.Location;
import com.tom.rv2ide.progress.ICancelChecker;
import java.util.Collections;
import java.util.List;

public class DefinitionProvider extends CancelableServiceProvider {
  private static final List<Location> NOT_SUPPORTED = Collections.emptyList();
  private final ICancelChecker cancelChecker;

  public DefinitionProvider(ICancelChecker cancelChecker) {
    super(cancelChecker);
    this.cancelChecker = cancelChecker;
  }

  @NonNull
  public DefinitionResult findDefinition(@NonNull DefinitionParams params) {
    abortIfCancelled();
    final JavaPsiDefinitionTarget target = JavaPsiServiceInterop.definition(params);
    if (target == null) {
      return new DefinitionResult(NOT_SUPPORTED);
    }

    final List<Location> locations;
    if (target.kind == JavaPsiDefinitionTarget.Kind.KOTLIN_SOURCE) {
      locations = KotlinSourceNavigation.findTarget(params, cancelChecker);
    } else {
      locations = target.locations;
    }

    abortIfCancelled();
    return new DefinitionResult(locations);
  }
}
