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

package org.gradle.api.internal.file.pattern;

@FunctionalInterface
public interface PatternMatcher {
    PatternMatcher MATCH_ALL = new PatternMatcher() {
        @Override
        public boolean test(String[] segments, boolean isFile) {
            return true;
        }

        @Override
        public PatternMatcher and(PatternMatcher other) {
            return other;
        }

        @Override
        public PatternMatcher or(PatternMatcher other) {
            return this;
        }
    };

    boolean test(String[] segments, boolean isFile);

    default PatternMatcher and(PatternMatcher other) {
        return (segments, isFile) -> this.test(segments, isFile) && other.test(segments, isFile);
    }

    default PatternMatcher or(PatternMatcher other) {
        return (segments, isFile) -> this.test(segments, isFile) || other.test(segments, isFile);
    }

    default PatternMatcher negate() {
        return (segments, isFile) -> !test(segments, isFile);
    }
}
