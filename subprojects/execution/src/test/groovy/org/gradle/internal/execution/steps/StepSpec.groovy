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

package org.gradle.internal.execution.steps

import groovy.transform.Memoized
import org.gradle.internal.execution.Context
import org.gradle.internal.execution.Step
import org.gradle.internal.execution.UnitOfWork
import org.gradle.internal.operations.BuildOperationType
import org.gradle.internal.operations.TestBuildOperationExecutor
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.junit.Rule
import spock.lang.Specification

import java.util.function.Consumer

abstract class StepSpec extends Specification {
    @Rule
    final TestNameTestDirectoryProvider temporaryFolder = TestNameTestDirectoryProvider.newInstance()
    final buildOperationExecutor = new TestBuildOperationExecutor()

    final displayName = "job ':test'"
    final identity = ":test"
    final delegate = Mock(Step)

    @Memoized
    UnitOfWork getWork() {
        Stub(UnitOfWork)
    }
    @Memoized
    Context getContext() {
        Stub(Context)
    }

    def setup() {
        context.work >> work
        work.displayName >> displayName
        work.identity >> identity
    }

    protected TestFile file(Object... path) {
        return temporaryFolder.file(path)
    }

    protected <D, R, T extends BuildOperationType<D, R>> void withOnlyOperation(
        Class<T> operationType,
        Consumer<TestBuildOperationExecutor.Log.TypedRecord<D, R>> verifier
    ) {
        assert buildOperationExecutor.log.records.size() == 1
        interaction {
            verifier.accept(buildOperationExecutor.log.mostRecent(operationType))
        }
    }
}
