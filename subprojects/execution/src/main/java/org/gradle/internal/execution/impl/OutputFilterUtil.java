/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.internal.execution.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.gradle.internal.file.FileType;
import org.gradle.internal.fingerprint.FileCollectionFingerprint;
import org.gradle.internal.fingerprint.FileSystemLocationFingerprint;
import org.gradle.internal.snapshot.DirectorySnapshot;
import org.gradle.internal.snapshot.FileSystemLocationSnapshot;
import org.gradle.internal.snapshot.FileSystemSnapshot;
import org.gradle.internal.snapshot.FileSystemSnapshotVisitor;
import org.gradle.internal.snapshot.MerkleDirectorySnapshotBuilder;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Filters out fingerprints that are not considered outputs. Entries that are considered outputs are:
 * <ul>
 * <li>an entry that did not exist before the execution, but exists after the execution</li>
 * <li>an entry that did exist before the execution, and has been changed during the execution</li>
 * <li>an entry that did wasn't changed during the execution, but was already considered an output during the previous execution</li>
 * </ul>
 */
public class OutputFilterUtil {

    public static ImmutableList<FileSystemSnapshot> filterOutputSnapshotBeforeExecution(FileCollectionFingerprint afterLastExecutionFingerprint, FileSystemSnapshot beforeExecutionOutputSnapshot) {
        Map<String, FileSystemLocationFingerprint> fingerprints = afterLastExecutionFingerprint.getFingerprints();
        SnapshotFilteringVisitor filteringVisitor = new SnapshotFilteringVisitor(snapshot -> {
            return snapshot.getType() != FileType.Missing && fingerprints.containsKey(snapshot.getAbsolutePath());
        });
        beforeExecutionOutputSnapshot.accept(filteringVisitor);
        return filteringVisitor.getNewRoots();
    }

    public static ImmutableList<FileSystemSnapshot> filterOutputSnapshotAfterExecution(@Nullable FileCollectionFingerprint afterLastExecutionFingerprint, FileSystemSnapshot beforeExecutionOutputSnapshot, FileSystemSnapshot afterExecutionOutputSnapshot) {
        Map<String, FileSystemLocationSnapshot> beforeExecutionSnapshots = getAllSnapshots(beforeExecutionOutputSnapshot);
        if (beforeExecutionSnapshots.isEmpty()) {
            return ImmutableList.of(afterExecutionOutputSnapshot);
        }

        Map<String, FileSystemLocationFingerprint> afterLastExecutionFingerprints = afterLastExecutionFingerprint != null
            ? afterLastExecutionFingerprint.getFingerprints()
            : ImmutableMap.of();

        SnapshotFilteringVisitor filteringVisitor = new SnapshotFilteringVisitor(afterExecutionSnapshot -> isOutputEntry(afterLastExecutionFingerprints, beforeExecutionSnapshots, afterExecutionSnapshot));
        afterExecutionOutputSnapshot.accept(filteringVisitor);

        // Are all file snapshots after execution accounted for as new entries?
        if (filteringVisitor.hasBeenFiltered()) {
            return filteringVisitor.getNewRoots();
        } else {
            return ImmutableList.of(afterExecutionOutputSnapshot);
        }
    }

    private static Map<String, FileSystemLocationSnapshot> getAllSnapshots(FileSystemSnapshot fingerprint) {
        GetAllSnapshotsVisitor allSnapshotsVisitor = new GetAllSnapshotsVisitor();
        fingerprint.accept(allSnapshotsVisitor);
        return allSnapshotsVisitor.getSnapshots();
    }

    /**
     * Decide whether an entry should be considered to be part of the output. See class Javadoc for definition of what is considered output.
     */
    private static boolean isOutputEntry(Map<String, FileSystemLocationFingerprint> afterPreviousExecutionFingerprints, Map<String, FileSystemLocationSnapshot> beforeExecutionSnapshots, FileSystemLocationSnapshot afterExecutionSnapshot) {
        if (afterExecutionSnapshot.getType() == FileType.Missing) {
            return false;
        }
        FileSystemLocationSnapshot beforeSnapshot = beforeExecutionSnapshots.get(afterExecutionSnapshot.getAbsolutePath());
        // Was it created during execution?
        if (beforeSnapshot == null) {
            return true;
        }
        // Was it updated during execution?
        if (!afterExecutionSnapshot.isContentAndMetadataUpToDate(beforeSnapshot)) {
            return true;
        }
        // Did we already consider it as an output after the previous execution?
        return afterPreviousExecutionFingerprints.containsKey(afterExecutionSnapshot.getAbsolutePath());
    }

    private static class GetAllSnapshotsVisitor implements FileSystemSnapshotVisitor {
        private final Map<String, FileSystemLocationSnapshot> snapshots = new HashMap<String, FileSystemLocationSnapshot>();

        @Override
        public boolean preVisitDirectory(DirectorySnapshot directorySnapshot) {
            snapshots.put(directorySnapshot.getAbsolutePath(), directorySnapshot);
            return true;
        }

        @Override
        public void visitFile(FileSystemLocationSnapshot fileSnapshot) {
            snapshots.put(fileSnapshot.getAbsolutePath(), fileSnapshot);
        }

        @Override
        public void postVisitDirectory(DirectorySnapshot directorySnapshot) {
        }

        public Map<String, FileSystemLocationSnapshot> getSnapshots() {
            return snapshots;
        }
    }

    private static class SnapshotFilteringVisitor implements FileSystemSnapshotVisitor {
        private final Predicate<FileSystemLocationSnapshot> predicate;
        private final ImmutableList.Builder<FileSystemSnapshot> newRootsBuilder = ImmutableList.builder();

        private boolean hasBeenFiltered;
        private MerkleDirectorySnapshotBuilder merkleBuilder;
        private boolean currentRootFiltered;
        private DirectorySnapshot currentRoot;

        public SnapshotFilteringVisitor(Predicate<FileSystemLocationSnapshot> predicate) {
            this.predicate = predicate;
        }

        @Override
        public boolean preVisitDirectory(DirectorySnapshot directorySnapshot) {
            if (merkleBuilder == null) {
                merkleBuilder = MerkleDirectorySnapshotBuilder.noSortingRequired();
                currentRoot = directorySnapshot;
                currentRootFiltered = false;
            }
            merkleBuilder.preVisitDirectory(directorySnapshot);
            return true;
        }

        @Override
        public void visitFile(FileSystemLocationSnapshot fileSnapshot) {
            if (!predicate.test(fileSnapshot)) {
                hasBeenFiltered = true;
                currentRootFiltered = true;
                return;
            }
            if (merkleBuilder == null) {
                newRootsBuilder.add(fileSnapshot);
            } else {
                merkleBuilder.visitFile(fileSnapshot);
            }
        }

        @Override
        public void postVisitDirectory(DirectorySnapshot directorySnapshot) {
            boolean isOutputDir = predicate.test(directorySnapshot);
            boolean includedDir = merkleBuilder.postVisitDirectory(isOutputDir);
            if (!includedDir) {
                currentRootFiltered = true;
                hasBeenFiltered = true;
            }
            if (merkleBuilder.isRoot()) {
                FileSystemLocationSnapshot result = merkleBuilder.getResult();
                if (result != null) {
                    newRootsBuilder.add(currentRootFiltered ? result : currentRoot);
                }
                merkleBuilder = null;
                currentRoot = null;
            }
        }
        public ImmutableList<FileSystemSnapshot> getNewRoots() {
            return newRootsBuilder.build();
        }

        public boolean hasBeenFiltered() {
            return hasBeenFiltered;
        }
    }
}
