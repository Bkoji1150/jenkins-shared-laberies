import java.net.URLEncoder

void call(List dockerBuildArgs=[], List customParams=[], Map dynamicSteps=[:]) {
    String buildStatusMessage = ''
    String VERSION
    Map PROPERTIES
    String CELL_FULL_NAME
    String BASE_BRANCH = 'master'
    String COMPONENT_TYPE
    String DEPLOY_ENVIRONMENT
    Map userDockerBuildArgs
    String sonarScanResults = null
    List defaultParams = [
        ['name': 'PROPERTY_FILE_PATH', 'defaultValue': 'pipeline.json', 'description': 'Path to the pipeline.json file in your repository'],
        ['name': 'DEPLOY_ENVIRONMENT', 'type': 'choice', 'choices': ['', 'SBX', 'DEV', 'TEST', 'PROD'], 'description': 'Triggers a deploy to the chosen environment. Leave blank to not trigger deploy to an environment. If you choose PROD the CD pipeline will have to be manually approved.'],
        ['name': 'CD_BRANCH', 'defaultValue': 'master', 'description': 'Specify the branch to trigger on the corresponding CD Pipeline. This parameter can be ignored if DEPLOY_ENVIRONMENT is left blank.'],
        ['name': 'TESTS_BRANCH', 'defaultValue': 'master', 'description': 'Specify the branch to trigger on the corresponding Test Pipeline. This parameter can be ignored if a Test Pipeline does not exist.'],
    ]

    properties([
        parameters ([
            // customParams _must_ come first to override the defaultParams
            utils.buildParams(customParams + defaultParams),
            utils.buildParams(dockerBuildArgs, 'docker'),
        ].collectMany { l -> l })
    ])

    pipeline {
        agent { label 'docker' }
        environment {
            REGISTRY_URL = '354979567826.dkr.ecr.us-east-1.amazonaws.com'
            STATIC_CONTEXT = "/tmp/test_workspace/${JOB_NAME}/${BUILD_NUMBER}"
        }
        stages {
            stage('Build Workspace') {
                steps {
                    script {
                        workspace.build()
                        if (fileExists(params.PROPERTY_FILE_PATH)) {
                            PROPERTIES = readJSON file: params.PROPERTY_FILE_PATH
                        } else {
                            error("Properties file (${params.PROPERTY_FILE_PATH}) does not exist!")
                        }
                        CELL_FULL_NAME = "${PROPERTIES.cellName}-${PROPERTIES.componentName}"
                        String baseBranch = PROPERTIES.baseBranch == null ? BASE_BRANCH : PROPERTIES.baseBranch
                        COMPONENT_TYPE = PROPERTIES.componentName.split('-')[-1]
                        VERSION = gitversion(baseBranch)
                        userDockerBuildArgs = PROPERTIES.dockerBuildArgs == null ? [:] : PROPERTIES.dockerBuildArgs
                    }
                }
            }
            stage('Code Quality') {
                tools {
                    jdk 'jdk'
                }
                    steps {
                        script {
                            if (codeQualityResult != null) {
                                withSonarQubeEnv(installationName: "sonar") {
                                sh "mvn clean package"
                    }
                        }
                            if (dynamicSteps.postCodeQuality) { dynamicSteps.postCodeQuality().call() }
                        }
                 }
                    // script {
                    //     if (dynamicSteps.preCodeQuality) { dynamicSteps.preCodeQuality().call() }
                    //     Boolean codeQualityResult = codeQuality(CELL_FULL_NAME, VERSION, PROPERTIES.sonarPropertiesFile)
                    //     if (codeQualityResult != null) {
                    //         // String qp = env.CHANGE_ID ? "pullRequest=${env.CHANGE_ID}" : "branch=${URLEncoder.encode(env.BRANCH_NAME, 'UTF-8')}"
                    //         // sonarScanResults = "<https://developer.hqr.hcqis.org/sonar/dashboard?id=${CELL_FULL_NAME}&${qp}|${codeQualityResult ? 'PASSED' : 'FAILED'}>"
                    //     }
                    //     if (dynamicSteps.postCodeQuality) { dynamicSteps.postCodeQuality().call() }
                    // }
                }
            
            stage('Build and Stage Application') {
                steps {
                    script { if (dynamicSteps.preBuildAndStageApplication) { dynamicSteps.preBuildAndStageApplication().call() } }
                    buildStageApplication(
                        VERSION,
                        COMPONENT_TYPE,
                        CELL_FULL_NAME,
                        PROPERTIES.codeType,
                        PROPERTIES.dockerfilePath == null ? 'Dockerfile' : PROPERTIES.dockerfilePath,
                        PROPERTIES.sourceBucketName,
                        PROPERTIES.sharedFunctionBucket,
                        PROPERTIES.dockerLambda == null ? false : PROPERTIES.dockerLambda,
                        userDockerBuildArgs + params.findAll { param -> param.key.matches('docker-(.*)') },
                        PROPERTIES.dockerBuildTarget
                    )
                    script { if (dynamicSteps.postBuildAndStageApplication) { dynamicSteps.postBuildAndStageApplication().call() } }
                }
            }
        }
        post {
            success {
                script {
                    buildStatusMessage = """
                        New image built and tagged.
                        Version: ${VERSION}
                    """.stripIndent()
                    if (sonarScanResults != null) {
                        buildStatusMessage += "SonarQube Report: ${sonarScanResults}"
                    }

                    String cdJobName = "${CELL_FULL_NAME}-cd/${URLEncoder.encode(params.CD_BRANCH, 'UTF-8')}"
                    Boolean cdJobExists = jenkins.model.Jenkins.instance.getItemByFullName(cdJobName) != null
                    if (params.DEPLOY_ENVIRONMENT !=  '') {
                        if (cdJobExists) {
                            build job: cdJobName,
                            parameters:
                            [
                                string(name: 'PROPERTY_FILE_PATH', value: "${params.PROPERTY_FILE_PATH}"),
                                string(name: 'ENVIRONMENT', value: "${params.DEPLOY_ENVIRONMENT}"),
                                booleanParam(name: 'DEPLOY_LATEST', value: true),
                                string(name:'TESTS_BRANCH', value: "${params.TESTS_BRANCH}"),
                                booleanParam(name:'AUTO_APPROVE', value: true)
                            ]
                        } else {
                            echo "CD Job '${cdJobName}' does not exist. Skipping trigger..."
                        }
                    }
                }
            }
            failure {
                script {
                    if (sonarScanResults != null) {
                         slackSend channel: '#general', color: 'Good', message: "SonarQube Report: ${sonarScanResults}"
                        buildStatusMessage = "SonarQube Report: ${sonarScanResults}"
                    }
                }
            }
            cleanup {
                script {
                    try {
                        workspace.tearDown(
                            CELL_FULL_NAME,
                            PROPERTIES.removeImage == null ? false : PROPERTIES.removeImage as Boolean
                        )
                    } catch (Exception e) {
                        echo 'An exception occurred while tearing down the workspace:'
                        echo e.getMessage()
                    }
                    // try {
                    //     metrics.publishCloudwatchBuildMetrics()
                    // } catch (Exception e) {
                    //     echo 'An exception occurred while publishing Cloudwatch Metrics:'
                    //     echo e.getMessage()
                    // }(

                    slackSend channel: '#general', color: 'Good', message: "SonarQube Report: ${sonarScanResults}"
                }
            }
        }
    }
}
