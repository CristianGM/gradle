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

package org.gradle.instantexecution

import groovy.json.JsonOutput

import org.gradle.instantexecution.serialization.PropertyFailure
import org.gradle.instantexecution.serialization.PropertyKind
import org.gradle.instantexecution.serialization.PropertyTrace

import org.gradle.internal.logging.ConsoleRenderer

import org.gradle.util.GFileUtils.copyURLToFile

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URL


class InstantExecutionReport(

    private
    val failures: List<PropertyFailure>,

    private
    val outputDirectory: File

) {
    val summary: String
        get() {
            val uniquePropertyFailures = failures.groupBy {
                propertyDescriptionFor(it) to it.message
            }
            return StringBuilder().apply {
                appendln("${uniquePropertyFailures.size} instant execution issues found:")
                uniquePropertyFailures.keys.forEach { (property, message) ->
                    append("  - ")
                    append(property)
                    append(": ")
                    appendln(message)
                }
                appendln("See the complete report at ${clickableUrlFor(reportFile)}")
            }.toString()
        }

    fun writeReportFiles() {
        outputDirectory.mkdirs()
        copyReportResources()
        writeJsFailures()
    }

    private
    fun copyReportResources() {
        listOf(
            reportFile.name,
            "instant-execution-report.js",
            "instant-execution-report.css",
            "kotlin.js"
        ).forEach { resourceName ->
            copyURLToFile(
                getResource(resourceName),
                outputDirectory.resolve(resourceName)
            )
        }
    }

    private
    fun writeJsFailures() {
        outputDirectory.resolve("instant-execution-failures.js").bufferedWriter().use { writer ->
            writer.run {
                appendln("function instantExecutionFailures() { return [")
                failures.forEach {
                    append(
                        JsonOutput.toJson(
                            mapOf(
                                "trace" to traceListOf(it),
                                "message" to it.message.fragments,
                                "error" to stackTraceStringOf(it)
                            )
                        )
                    )
                    appendln(",")
                }
                appendln("];}")
            }
        }
    }

    private
    fun stackTraceStringOf(failure: PropertyFailure): String? =
        (failure as? PropertyFailure.Error)?.exception?.let {
            stackTraceStringFor(it)
        }

    private
    fun stackTraceStringFor(error: Throwable): String =
        StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()

    private
    fun traceListOf(failure: PropertyFailure): List<Map<String, Any>> = mutableListOf<Map<String, Any>>().also { result ->
        fun collectTrace(current: PropertyTrace): Unit = current.run {
            when (this) {
                is PropertyTrace.Property -> {
                    result.add(
                        when (kind) {
                            PropertyKind.Field -> mapOf(
                                "kind" to kind.name,
                                "name" to name,
                                "declaringType" to firstTypeFrom(trace).name
                            )
                            else -> mapOf(
                                "kind" to kind.name,
                                "name" to name,
                                "task" to taskPathFrom(trace)
                            )
                        }
                    )
                    collectTrace(trace)
                }
                is PropertyTrace.Task -> {
                    result.add(
                        mapOf(
                            "kind" to "Task",
                            "path" to path,
                            "type" to type.name
                        )
                    )
                }
                is PropertyTrace.Bean -> {
                    result.add(
                        mapOf(
                            "kind" to "Bean",
                            "type" to type.name
                        )
                    )
                    collectTrace(trace)
                }
                PropertyTrace.Gradle -> {
                    result.add(
                        mapOf("kind" to "Gradle")
                    )
                }
                PropertyTrace.Unknown -> {
                    result.add(
                        mapOf("kind" to "Unknown")
                    )
                }
            }
        }
        collectTrace(failure.trace)
    }

    private
    fun propertyDescriptionFor(failure: PropertyFailure): String = failure.trace.run {
        when (this) {
            is PropertyTrace.Property -> simplePropertyDescription()
            else -> toString()
        }
    }

    private
    fun getResource(path: String): URL = javaClass.getResource(path).also {
        require(it != null) { "Resource `$path` could not be found!" }
    }

    private
    fun PropertyTrace.Property.simplePropertyDescription(): String = when (kind) {
        PropertyKind.Field -> "field '$name' from type '${firstTypeFrom(trace).name}'"
        else -> "$kind '$name' of '${taskPathFrom(trace)}'"
    }

    private
    fun taskPathFrom(trace: PropertyTrace): String =
        trace.sequence.filterIsInstance<PropertyTrace.Task>().first().path

    private
    fun firstTypeFrom(trace: PropertyTrace): Class<*> =
        trace.sequence.mapNotNull { typeFrom(it) }.first()

    private
    fun typeFrom(trace: PropertyTrace): Class<out Any>? = when (trace) {
        is PropertyTrace.Bean -> trace.type
        is PropertyTrace.Task -> trace.type
        else -> null
    }

    private
    val reportFile
        get() = outputDirectory.resolve("instant-execution-report.html")
}


private
fun clickableUrlFor(file: File) = ConsoleRenderer().asClickableFileUrl(file)
