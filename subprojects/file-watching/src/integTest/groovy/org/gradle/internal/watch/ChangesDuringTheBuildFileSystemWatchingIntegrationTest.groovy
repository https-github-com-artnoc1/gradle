/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.internal.watch

import org.gradle.integtests.fixtures.ToBeFixedForInstantExecution
import org.gradle.test.fixtures.server.http.BlockingHttpServer
import org.junit.Rule

class ChangesDuringTheBuildFileSystemWatchingIntegrationTest extends AbstractFileSystemWatchingIntegrationTest {
    @Rule
    BlockingHttpServer server = new BlockingHttpServer()

    def setup() {
        executer.requireDaemon()
        server.start()
        buildFile << """
            import org.gradle.internal.file.FileType
            import org.gradle.internal.snapshot.*
            import org.gradle.internal.vfs.*

            task waitForUserChanges {
                doLast {
                    ${server.callFromBuild("userInput")}
                }
            }

            gradle.buildFinished {
                def projectRoot = project.projectDir.absolutePath
                def root = gradle.services.get(VfsRootReference)
                int filesInVfs = 0
                root.get().visitSnapshotRoots { snapshot ->
                    snapshot.accept(new FileSystemSnapshotVisitor() {
                        @Override
                        void visitFile(CompleteFileSystemLocationSnapshot fileSnapshot) {
                            if (fileSnapshot.type == FileType.RegularFile && fileSnapshot.absolutePath.startsWith(projectRoot)) {
                                filesInVfs++
                            }
                        }

                        @Override
                        boolean preVisitDirectory(CompleteDirectorySnapshot directorySnapshot) { return true }
                        @Override
                        void postVisitDirectory(CompleteDirectorySnapshot directorySnapshot) {}
                    })
                }
                println("Project files in VFS: \$filesInVfs")
            }
        """
    }

    @ToBeFixedForInstantExecution(because = "Cannot use buildFinished listener")
    def "detects input file change just before the task is executed"() {
        def inputFile = file("input.txt")
        buildFile << """
            def inputFile = file("input.txt")
            def outputFile = file("build/output.txt")

            task consumer {
                inputs.file(inputFile)
                outputs.file(outputFile)
                doLast {
                    outputFile.text = inputFile.text
                }
                dependsOn(waitForUserChanges)
            }
        """

        when:
        runWithRetentionAndDoChangesWhen("consumer", "userInput") {
            inputFile.text = "initial"
            waitForChangesToBePickedUp()
        }
        then:
        executedAndNotSkipped(":consumer")
        // TODO: sometimes, the changes from the same build are picked up
        projectFilesInVfs >= 1

        when:
        runWithRetentionAndDoChangesWhen("consumer", "userInput") {
            inputFile.text = "changed"
            waitForChangesToBePickedUp()
        }
        then:
        executedAndNotSkipped(":consumer")
        receivedFileSystemEventsInCurrentBuild >= 1
        projectFilesInVfs == 2
    }

    @ToBeFixedForInstantExecution(because = "Cannot use buildFinished listener")
    def "detects input file change after the task has been executed"() {
        def inputFile = file("input.txt")
        def outputFile = file("build/output.txt")
        buildFile << """
            def inputFile = file("input.txt")
            def outputFile = file("build/output.txt")

            task consumer {
                inputs.file(inputFile)
                outputs.file(outputFile)
                doLast {
                    outputFile.text = inputFile.text
                }
            }

            waitForUserChanges.dependsOn(consumer)
        """

        when:
        inputFile.text = "initial"
        runWithRetentionAndDoChangesWhen("waitForUserChanges", "userInput") {
            inputFile.text = "changed"
            waitForChangesToBePickedUp()
        }
        then:
        executedAndNotSkipped(":consumer")
        outputFile.text == "initial"
        projectFilesInVfs == 1

        when:
        runWithRetentionAndDoChangesWhen("waitForUserChanges", "userInput") {
            inputFile.text = "changedAgain"
            waitForChangesToBePickedUp()
        }
        then:
        executedAndNotSkipped(":consumer")
        outputFile.text == "changed"
        receivedFileSystemEventsInCurrentBuild >= 1
        projectFilesInVfs == 1

        when:
        server.expect("userInput")
        withWatchFs().run("waitForUserChanges")
        then:
        executedAndNotSkipped(":consumer")
        outputFile.text == "changedAgain"
        projectFilesInVfs == 2
    }

    private void runWithRetentionAndDoChangesWhen(String task, String expectedCall, Closure action) {
        def handle = withWatchFs().executer.withTasks(task).start()
        def userInput = server.expectAndBlock(expectedCall)
        userInput.waitForAllPendingCalls()
        action()
        userInput.releaseAll()
        result = handle.waitForFinish()
    }

    int getProjectFilesInVfs() {
        def retainedInformation = result.getOutputLineThatContains("Project files in VFS: ")
        def numberMatcher = retainedInformation =~ /Project files in VFS: (\d+)/
        return numberMatcher[0][1] as int

    }
}
