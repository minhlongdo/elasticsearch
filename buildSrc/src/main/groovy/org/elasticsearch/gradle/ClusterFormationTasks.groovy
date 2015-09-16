package org.elasticsearch.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec

/**
 * A helper for creating tasks to build a cluster that is used by a task, and tear down the cluster when the task is finished.
 */
class ClusterFormationTasks {

    /**
     * Adds dependent tasks to the given task to start a cluster with the given configuration.
     * Also adds a finalize task to stop the cluster.
     */
    static void addTasks(Task task, ClusterConfiguration config) {
        addZipConfiguration(task.project)
        File clusterDir = new File(task.project.buildDir, 'cluster' + File.separator + task.name)
        if (config.numNodes == 1) {
            addNodeStartupTasks(task, config, clusterDir)
            addNodeStopTask(task, clusterDir)
        } else {
            for (int i = 0; i < config.numNodes; ++i) {
                File nodeDir = new File(clusterDir, "node${i}")
                addNodeStartupTasks(task, config, nodeDir)
                addNodeStopTask(task, nodeDir)
            }
        }
    }

    static void addNodeStartupTasks(Task task, ClusterConfiguration config, File baseDir) {
        Project project = task.project
        Task install = project.tasks.create(name: task.name + '#install', type: Copy, dependsOn: project.configurations.elasticsearchZip.buildDependencies) {
            from project.zipTree(project.configurations.elasticsearchZip.asPath)
            into baseDir
        }
        File home = new File(baseDir, "elasticsearch-${ElasticsearchProperties.version}")
        Task clean = project.tasks.create(name: "${task.name}#clean", type: Delete, dependsOn: install) {
            delete new File(home, 'plugins'), new File(home, 'data'), new File(home, 'logs')
        }

        Task setup = clean // chain setup tasks to maintain their order
        for (Map.Entry<String, String> command : config.setupConfig.commands.entrySet()) {
            Task nextSetup = project.tasks.create(name: "${task.name}#${command.getKey()}", type: Exec, dependsOn: setup) {
                workingDir home
                executable 'sh'
                args command.getValue()
                // only show output on failure, when not in info or debug mode
                if (logger.isInfoEnabled() == false) {
                    standardOutput = new ByteArrayOutputStream()
                    errorOutput = standardOutput
                    ignoreExitValue = true
                    doLast {
                        if (execResult.exitValue != 0) {
                            logger.error(standardOutput.toString())
                            throw new GradleException("Process '${command.getValue().join(' ')}' finished with non-zero exit value ${execResult.exitValue}")
                        }
                    }
                }
            }
            setup = nextSetup
        }

        String clusterName = "test${task.path.replace(':', '_')}"
        File pidFile = pidFile(baseDir)
        Task start = project.tasks.create(name: "${task.name}#start", type: Exec, dependsOn: setup) {
            workingDir home
            executable 'sh'
            List esArgs = [
                'bin/elasticsearch',
                '-d', // daemonize!
                "-Des.cluster.name=${clusterName}",
                "-Des.http.port=${config.httpPort}",
                "-Des.transport.tcp.port=${config.transportPort}",
                "-Des.pidfile=${pidFile}"
            ]
            esArgs.addAll(config.sysProps.collect {key, value -> "-D${key}=${value}"})
            args esArgs
            errorOutput = new ByteArrayOutputStream()
            doLast {
                if (errorOutput.toString().isEmpty() == false) {
                    logger.error(errorOutput.toString())
                    new File(home, 'logs' + File.separator + clusterName + '.log').eachLine {
                        line -> logger.error(line)
                    }
                    throw new GradleException('Failed to start elasticsearch')
                }
                ant.waitfor(maxwait: '30', maxwaitunit: 'second', checkevery: '500', checkeveryunit: 'millisecond', timeoutproperty: "failed${task.name}#start") {
                    and {
                        resourceexists {
                            file file: pidFile.toString()
                        }
                        http(url: "http://localhost:${config.httpPort}")
                    }
                }
                if (ant.properties.containsKey("failed${task.name}#start")) {
                    new File(home, 'logs' + File.separator + clusterName + '.log').eachLine {
                        line -> logger.error(line)
                    }
                    throw new GradleException('Failed to start elasticsearch')
                }
            }
        }
        task.dependsOn(start)
    }

    static void addNodeStopTask(Task task, File baseDir) {
        LazyPidReader pidFile = new LazyPidReader(pidFile: pidFile(baseDir))
        Task stop = task.project.tasks.create(name: task.name + '#stop', type: Exec) {
            executable 'kill'
            args '-9', pidFile
            doLast {
                // TODO: wait for pid to close, or kill -9 and fail
            }
        }
        task.finalizedBy(stop)
    }

    /** Delays reading a pid file until needing to use the pid */
    static class LazyPidReader {
        File pidFile
        @Override
        String toString() {
            return pidFile.text.stripMargin()
        }
    }

    static File pidFile(File dir) {
        return new File(dir, 'es.pid')
    }

    static void addZipConfiguration(Project project) {
        String elasticsearchVersion = ElasticsearchProperties.version
        project.configurations {
            elasticsearchZip
        }
        project.dependencies {
            elasticsearchZip "org.elasticsearch.distributions.zip:elasticsearch:${elasticsearchVersion}"
        }
    }
}
