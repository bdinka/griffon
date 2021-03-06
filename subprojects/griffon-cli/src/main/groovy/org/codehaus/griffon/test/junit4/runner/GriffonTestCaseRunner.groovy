/*
 * Copyright 2009-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package org.codehaus.griffon.test.junit4.runner

import griffon.core.GriffonApplication
import java.lang.reflect.InvocationTargetException
import org.codehaus.griffon.test.GriffonTestTargetPattern
import org.codehaus.griffon.test.junit4.JUnit4GriffonTestType
import org.codehaus.griffon.test.support.GriffonTestMode
import org.junit.internal.runners.statements.Fail
import org.junit.internal.runners.statements.RunAfters
import org.junit.internal.runners.statements.RunBefores
import org.junit.rules.MethodRule
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.Statement
import org.springframework.util.ReflectionUtils

class GriffonTestCaseRunner extends BlockJUnit4ClassRunner {

    final mode
    final app
    final testTargetPatterns

    GriffonTestCaseRunner(Class testClass, GriffonTestTargetPattern[] testTargetPatterns) {
        this(testClass, null, null, testTargetPatterns)
    }

    GriffonTestCaseRunner(Class testClass, GriffonTestMode mode, GriffonApplication app, GriffonTestTargetPattern[] testTargetPatterns) {
        super(testClass)
        this.mode = mode
        this.app = app
        this.testTargetPatterns = testTargetPatterns
        validateMode()
    }

    protected validateMode() {
        if (mode && app == null) {
            throw new IllegalStateException("mode $mode requires an application")
        }
    }

    /**
     * This is the only suitable hook that allows us to wrap the before/after
     * methods in transactions etc. Unfortunately, that means we have to copy
     * most the implementation from BlockJUnit4ClassRunner.
     */
    protected Statement methodBlock(FrameworkMethod method) {
        if (mode) {
            def test = null

            // Create test instantiates the test object reflectively, so we unwrap
            // the InvocationTargetException if an exception occurs.
            try {
                test = createTest()
            } catch (InvocationTargetException e) {
                return new Fail(e.targetException)
            }

            def statement = methodInvoker(method, test)
            statement = possiblyExpectingExceptions(method, test, statement)
            statement = withPotentialTimeout(method, test, statement)
            statement = withBefores(method, test, statement)
            statement = withAfters(method, test, statement)

            statement = withRules(method, test, statement)

            withGriffonTestEnvironment(statement, test)
        } else {
            // fast lane for unit tests
            super.methodBlock(method)
        }
    }

    private Statement withRules(FrameworkMethod method, Object target,
                                Statement statement) {
        Statement result = statement;
        for (MethodRule each: rules(target))
            result = each.apply(result, method, target);
        return result;
    }

    protected withGriffonTestEnvironment(Statement statement, Object test) {
        if (!mode) {
            throw new IllegalStateException("withGriffonTestEnvironment can not be called without a test mode set")
        }

        def interceptor = mode.createInterceptor(test, app, JUnit4GriffonTestType.SUFFIXES as String[])
        new GriffonTestEnvironmentStatement(statement, test, interceptor)
    }

    protected List<FrameworkMethod> computeTestMethods() {
        def annotated = super.computeTestMethods()
        testClass.javaClass.methods.each { method ->
            if (method.name.size() > 4 && method.name[0..3] == "test" && method.parameterTypes.size() == 0) {
                def existing = annotated.find { it.method == method }
                if (!existing) {
                    annotated << new FrameworkMethod(method)
                }
            }
        }

        def methodMatchingTargetPatterns = testTargetPatterns?.findAll { it.methodTargeting }
        if (methodMatchingTargetPatterns) { // slow lane, filter methods
            def patternsForThisClass = testTargetPatterns.findAll {
                it.matchesClass(testClass.javaClass.name, JUnit4GriffonTestType.SUFFIXES as String[])
            }
            if (patternsForThisClass) {
                annotated.findAll { frameworkMethod ->
                    patternsForThisClass.any { pattern -> pattern.matchesMethod(frameworkMethod.name) }
                }
            } else {
                annotated
            }
        } else { // fast lane
            annotated
        }
    }

    protected Statement withBefores(FrameworkMethod method, Object target, Statement statement) {
        def superResult = super.withBefores(method, target, statement)
        if (superResult.is(statement)) {
            def setupMethod = ReflectionUtils.findMethod(testClass.javaClass, 'setUp')
            if (setupMethod) {
                setupMethod.accessible = true
                def setUp = new FrameworkMethod(setupMethod)
                new RunBefores(statement, [setUp], target)
            }
            else {
                superResult
            }
        } else {
            superResult
        }
    }

    protected Statement withAfters(FrameworkMethod method, Object target, Statement statement) {
        def superResult = super.withAfters(method, target, statement)
        if (superResult.is(statement)) {
            def tearDownMethod = ReflectionUtils.findMethod(testClass.javaClass, 'tearDown')
            if (tearDownMethod) {
                tearDownMethod.accessible = true
                def tearDown = new FrameworkMethod(tearDownMethod)
                new RunAfters(statement, [tearDown], target)
            } else {
                superResult
            }
        } else {
            superResult
        }
    }
}
