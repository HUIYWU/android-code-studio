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
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop;
import com.tom.rv2ide.lsp.models.SignatureHelp;
import com.tom.rv2ide.lsp.models.SignatureHelpParams;
import com.tom.rv2ide.progress.ICancelChecker;
import java.util.Collections;

public final class SignatureProvider extends CancelableServiceProvider {
  public SignatureProvider(ICancelChecker cancelChecker) {
    super(cancelChecker);
  }

  @NonNull
  public SignatureHelp signatureHelp(@NonNull SignatureHelpParams params) {
    abortIfCancelled();
    final SignatureHelp result = JavaPsiServiceInterop.signatureHelp(params);
    abortIfCancelled();
    return result == null ? new SignatureHelp(Collections.emptyList(), -1, -1) : result;
  }
}
