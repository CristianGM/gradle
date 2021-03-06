/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.compile.incremental.recomp;

import org.gradle.api.file.FileTree;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.tasks.compile.JavaCompileSpec;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.internal.Factory;
import org.gradle.work.FileChange;
import org.gradle.work.InputChanges;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

public class GroovyRecompilationSpecProvider extends AbstractRecompilationSpecProvider {
    private final InputChanges inputChanges;
    private final Iterable<FileChange> sourceChanges;
    private final GroovySourceFileClassNameConverter sourceFileClassNameConverter;

    public GroovyRecompilationSpecProvider(FileOperations fileOperations,
                                           FileTree sources,
                                           InputChanges inputChanges,
                                           Iterable<FileChange> sourceChanges,
                                           GroovySourceFileClassNameConverter sourceFileClassNameConverter) {
        super(fileOperations, sources);
        this.inputChanges = inputChanges;
        this.sourceChanges = sourceChanges;
        this.sourceFileClassNameConverter = sourceFileClassNameConverter;
    }

    @Override
    public boolean isIncremental() {
        return inputChanges.isIncremental();
    }

    @Override
    public RecompilationSpec provideRecompilationSpec(CurrentCompilation current, PreviousCompilation previous) {
        RecompilationSpec spec = new RecompilationSpec();
        if (sourceFileClassNameConverter.isEmpty()) {
            spec.setFullRebuildCause("no source class mapping file found", null);
            return spec;
        }

        processClasspathChanges(current, previous, spec);
        processOtherChanges(previous, spec);

        spec.getClassesToProcess().addAll(previous.getTypesToReprocess());
        return spec;
    }

    @Override
    public void initializeCompilation(JavaCompileSpec spec, RecompilationSpec recompilationSpec) {
        if (!recompilationSpec.isBuildNeeded()) {
            spec.setSourceFiles(Collections.emptySet());
            spec.setClasses(Collections.emptySet());
            return;
        }

        Factory<PatternSet> patternSetFactory = fileOperations.getFileResolver().getPatternSetFactory();
        PatternSet classesToDelete = patternSetFactory.create();
        PatternSet filesToRecompile = patternSetFactory.create();

        prepareFilePatterns(recompilationSpec.getRelativeSourcePathsToCompile(), classesToDelete, filesToRecompile);

        spec.setSourceFiles(sourceTree.matching(filesToRecompile));
        includePreviousCompilationOutputOnClasspath(spec);
        addClassesToProcess(spec, recompilationSpec);

        deleteStaleFilesIn(classesToDelete, spec.getDestinationDir());
    }

    private void prepareFilePatterns(Set<String> relativeSourcePathsToCompile, PatternSet classesToDelete, PatternSet filesToRecompilePatterns) {
        for (String relativeSourcePath : relativeSourcePathsToCompile) {
            filesToRecompilePatterns.include(relativeSourcePath);

            sourceFileClassNameConverter.getClassNames(relativeSourcePath)
                .stream()
                .map(staleClass -> staleClass.replaceAll("\\.", "/").concat(".class"))
                .forEach(classesToDelete::include);
        }
    }

    private void processOtherChanges(PreviousCompilation previous, RecompilationSpec spec) {
        if (spec.getFullRebuildCause() != null) {
            return;
        }
        SourceFileChangeProcessor sourceFileChangeProcessor = new SourceFileChangeProcessor(previous);

        for (FileChange fileChange : sourceChanges) {
            if (spec.getFullRebuildCause() != null) {
                return;
            }

            File changedFile = fileChange.getFile();
            String relativeFilePath = fileChange.getNormalizedPath();

            Collection<String> changedClasses = sourceFileClassNameConverter.getClassNames(relativeFilePath);
            spec.getRelativeSourcePathsToCompile().add(relativeFilePath);
            sourceFileChangeProcessor.processChange(changedFile, changedClasses, spec);
        }

        for (String className : spec.getClassesToCompile()) {
            if (spec.getFullRebuildCause() != null) {
                return;
            }

            Optional<String> relativeSourceFile = sourceFileClassNameConverter.getRelativeSourcePath(className);
            if (relativeSourceFile.isPresent()) {
                spec.getRelativeSourcePathsToCompile().add(relativeSourceFile.get());
            } else {
                spec.setFullRebuildCause("Can't find source file of class " + className, null);
            }
        }
    }
}
