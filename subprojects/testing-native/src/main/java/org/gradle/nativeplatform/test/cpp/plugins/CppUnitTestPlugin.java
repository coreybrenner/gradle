/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.nativeplatform.test.cpp.plugins;

import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppPlatform;
import org.gradle.language.cpp.ProductionCppComponent;
import org.gradle.language.cpp.plugins.CppBasePlugin;
import org.gradle.language.internal.NativeComponentFactory;
import org.gradle.language.nativeplatform.internal.toolchains.ToolChainSelector;
import org.gradle.nativeplatform.tasks.AbstractLinkTask;
import org.gradle.nativeplatform.tasks.InstallExecutable;
import org.gradle.nativeplatform.test.cpp.CppTestSuite;
import org.gradle.nativeplatform.test.cpp.internal.DefaultCppTestExecutable;
import org.gradle.nativeplatform.test.cpp.internal.DefaultCppTestSuite;
import org.gradle.nativeplatform.test.plugins.NativeTestingBasePlugin;
import org.gradle.nativeplatform.test.tasks.RunTestExecutable;

import javax.inject.Inject;


/**
 * A plugin that sets up the infrastructure for testing C++ binaries using a simple test executable.
 *
 * Gradle will create a {@link RunTestExecutable} task that relies on the exit code of the binary.
 *
 * @since 4.4
 */
@Incubating
public class CppUnitTestPlugin implements Plugin<ProjectInternal> {
    private final NativeComponentFactory componentFactory;
    private final ToolChainSelector toolChainSelector;

    @Inject
    public CppUnitTestPlugin(NativeComponentFactory componentFactory, ToolChainSelector toolChainSelector) {
        this.componentFactory = componentFactory;
        this.toolChainSelector = toolChainSelector;
    }

    @Override
    public void apply(final ProjectInternal project) {
        project.getPluginManager().apply(CppBasePlugin.class);
        project.getPluginManager().apply(NativeTestingBasePlugin.class);

        // Add the unit test and extension
        final DefaultCppTestSuite testComponent = componentFactory.newInstance(CppTestSuite.class, DefaultCppTestSuite.class, "test");
        project.getExtensions().add(CppTestSuite.class, "unitTest", testComponent);
        project.getComponents().add(testComponent);

        testComponent.getBaseName().set(project.getName() + "Test");

        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(final Project project) {
                ToolChainSelector.Result<CppPlatform> result = toolChainSelector.select(CppPlatform.class);
                testComponent.addExecutable("executable", result.getTargetPlatform(), result.getToolChain(), result.getPlatformToolProvider());

                final TaskContainer tasks = project.getTasks();
                final ProductionCppComponent mainComponent = project.getComponents().withType(ProductionCppComponent.class).findByName("main");
                if (mainComponent != null) {
                    testComponent.getTestedComponent().set(mainComponent);
                }

                testComponent.getBinaries().whenElementKnown(DefaultCppTestExecutable.class, new Action<DefaultCppTestExecutable>() {
                    @Override
                    public void execute(final DefaultCppTestExecutable executable) {
                        if (mainComponent != null) {
                            // TODO: This should be modeled as a kind of dependency vs wiring binaries together directly.
                            mainComponent.getBinaries().whenElementFinalized(new Action<CppBinary>() {
                                @Override
                                public void execute(CppBinary cppBinary) {
                                    if (cppBinary == mainComponent.getDevelopmentBinary().get()) {
                                        AbstractLinkTask linkTest = executable.getLinkTask().get();
                                        linkTest.source(cppBinary.getObjects());
                                    }
                                }
                            });
                        }

                        // TODO: Replace with native test task
                        final RunTestExecutable testTask = tasks.create(executable.getNames().getTaskName("run"), RunTestExecutable.class);
                        testTask.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
                        testTask.setDescription("Executes C++ unit tests.");

                        final InstallExecutable installTask = executable.getInstallTask().get();
                        testTask.onlyIf(new Spec<Task>() {
                            @Override
                            public boolean isSatisfiedBy(Task element) {
                                return executable.getInstallDirectory().get().getAsFile().exists();
                            }
                        });
                        testTask.setExecutable(installTask.getRunScript());
                        testTask.dependsOn(testComponent.getTestBinary().get().getInstallDirectory());
                        // TODO: Honor changes to build directory
                        testTask.setOutputDir(project.getLayout().getBuildDirectory().dir("test-results/" + executable.getNames().getDirName()).get().getAsFile());
                        executable.getRunTask().set(testTask);
                    }
                });

                testComponent.getBinaries().realizeNow();
            }
        });
    }
}
