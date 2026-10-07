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

package com.tom.rv2ide.lsp.java.providers;

import android.text.TextUtils;
import androidx.annotation.NonNull;
import com.tom.rv2ide.lsp.api.IServerSettings;
import com.tom.rv2ide.lsp.java.compiler.JavaCompilerService;
import com.tom.rv2ide.lsp.java.compiler.SynchronizedTask;
import com.tom.rv2ide.lsp.java.kotlin.KotlinJvmSourceNavigator;
import com.tom.rv2ide.preferences.internal.JavaPreferences;
import com.tom.rv2ide.lsp.java.providers.definition.ErroneousDefinitionProvider;
import com.tom.rv2ide.lsp.java.providers.definition.IJavaDefinitionProvider;
import com.tom.rv2ide.lsp.java.providers.definition.KotlinDefinitionFallback;
import com.tom.rv2ide.lsp.java.providers.definition.LocalDefinitionProvider;
import com.tom.rv2ide.lsp.java.providers.definition.RemoteDefinitionProvider;
import com.tom.rv2ide.lsp.java.utils.NavigationHelper;
import com.tom.rv2ide.lsp.models.DefinitionParams;
import com.tom.rv2ide.lsp.models.DefinitionResult;
import com.tom.rv2ide.models.Location;
import com.tom.rv2ide.models.Position;
import com.tom.rv2ide.progress.ICancelChecker;
import com.tom.rv2ide.utils.DocumentUtils;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import jdkx.lang.model.element.Element;
import jdkx.lang.model.element.TypeElement;
import jdkx.lang.model.type.TypeKind;
import jdkx.tools.JavaFileObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefinitionProvider extends CancelableServiceProvider {

  public static final List<Location> NOT_SUPPORTED = Collections.emptyList();
  private static final Logger LOG = LoggerFactory.getLogger(DefinitionProvider.class);
  private final JavaCompilerService compiler;
  private final IServerSettings settings;
  private Path file;
  private Position position;
  private int line, column;

  public DefinitionProvider(JavaCompilerService compiler, IServerSettings settings,
      ICancelChecker cancelChecker) {
    super(cancelChecker);
    this.compiler = compiler;
    this.settings = settings;
  }

  @NonNull
  public DefinitionResult findDefinition(@NonNull DefinitionParams params) {
    this.file = params.getFile();

    // 1-based line and column index
    this.line = params.getPosition().getLine() + 1;
    this.column = params.getPosition().getColumn() + 1;
    this.position = new Position(this.line, this.column);
    final List<Location> locations = findDefinition();

    LOG.debug("Found {} definitions...", locations.size());
    return new DefinitionResult(locations);
  }

  public List<Location> findDefinition() {
    abortIfCancelled();
    final SynchronizedTask compile = compiler.compile(file);
    abortIfCancelled();
    final Element element =
        compile.get(task -> NavigationHelper.findElement(task, file, line, column, this));

    if (element == null) {
      LOG.debug("Cannot find javac element at line: {} and column: {}; trying Kotlin fallback", line, column);
      return KotlinDefinitionFallback.find(compiler, file, line - 1, column - 1);
    }

    // Java-to-Kotlin source navigation is an opt-in language-service capability.
    // Do not let an element originating from a stale classpath or previous task bypass the
    // recognition preference and trigger Kotlin source/index work while the feature is disabled.
    final Location kotlinLocation = JavaPreferences.INSTANCE.isJavaKotlinRecognitionEnabled()
        ? KotlinJvmSourceNavigator.find(compiler.getModule(), element)
        : null;
    if (kotlinLocation != null) {
      return Collections.singletonList(kotlinLocation);
    }

    IJavaDefinitionProvider provider = null;

    if (element.asType().getKind() == TypeKind.ERROR) {
      provider = new ErroneousDefinitionProvider(position, file, compiler, settings, this);
    } else if (NavigationHelper.isLocal(element)) {
      provider = new LocalDefinitionProvider(position, file, compiler, settings, this);
    }

    if (provider == null) {
      final String className = className(element);
      if (TextUtils.isEmpty(className)) {
        LOG.debug("No Java class name found for element: {}; trying Kotlin fallback", element);
        return KotlinDefinitionFallback.find(compiler, file, line - 1, column - 1);
      }

      final Optional<JavaFileObject> optional = compiler.findAnywhere(className);
      if (!optional.isPresent()) {
        LOG.debug("Cannot find Java source file for class: {}; trying Kotlin fallback", className);
        return KotlinDefinitionFallback.find(compiler, file, line - 1, column - 1);
      }

      final JavaFileObject jfo = optional.get();
      if (DocumentUtils.isSameFile(Paths.get(jfo.toUri()), file)) {
        provider = new LocalDefinitionProvider(position, file, compiler, settings, this);
      } else {
        provider =
            new RemoteDefinitionProvider(position, file, compiler, settings, this).setOtherFile(jfo);
      }
    }

    final List<Location> locations = provider.findDefinition(element);
    if (!locations.isEmpty()) {
      return locations;
    }
    return KotlinDefinitionFallback.find(compiler, file, line - 1, column - 1);
  }

  private String className(Element element) {
    while (element != null) {
      abortIfCancelled();
      if (element instanceof TypeElement) {
        TypeElement type = (TypeElement) element;
        return type.getQualifiedName().toString();
      }
      element = element.getEnclosingElement();
    }
    return "";
  }
}
