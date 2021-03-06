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

package org.gradle.util


import org.gradle.internal.classloader.ClassLoaderHierarchyHasher
import org.gradle.internal.hash.HashCode
import org.gradle.internal.snapshot.ValueSnapshotter
import org.gradle.internal.snapshot.impl.DefaultValueSnapshotter

class SnapshotTestUtil {
    static ValueSnapshotter valueSnapshotter() {
        return new DefaultValueSnapshotter(new ClassLoaderHierarchyHasher() {
            @Override
            HashCode getClassLoaderHash(ClassLoader classLoader) {
                return HashCode.fromInt(classLoader.hashCode())
            }
        }, null)
    }
}
