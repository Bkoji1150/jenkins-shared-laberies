import java.net.URLEncoder

void call(List terraformVars = [], List customParams = [], List<Map> applicationSource = [[terraformVar: 's3_key', path: '', source: 's3'],]) {
    String buildStatusMessage = ''
    String VERSION
    Map PROPERTIES
    String CELL_FULL_NAME
    String COMPONENT_TYPE
    Map terraformParams
    Map applicationSourceBuiltParams
    List defaultParams = [
            ['type': 'string', 'name': 'PROPERTY_FILE_PATH', 'defaultValue': 'pipeline.json', 'description': 'Path to the pipeline.json file in your repository'],
            ['type': 'string', 'name': 'TESTS_BRANCH', 'defaultValue': 'master', 'description': 'Specify the branch to trigger on the corresponding Test Pipeline.This parameter can be ignored if a Test Pipeline does not exist.'],
            ['type': 'choice', 'name': 'ENVIRONMENT', 'choices': ['SBX', 'DEV', 'TEST', 'PROD'], 'description': 'Deploy to the chosen environment. If you choose PROD the pipeline will have to be manually approved regardless of the AUTO_APPROVE choice.'],
            ['type': 'booleanParam', 'name': 'AUTO_APPROVE', 'defaultValue': false, 'description': 'If checked, will automatically apply the terraform plan of the deployment.'],
    ]
    List applicationSourceParams = applicationSource.collect { path ->
        return [
            'type': 'booleanParam',
            'name': "${path.terraformVar}_DEPLOY_LATEST".toUpperCase(),
            'defaultValue': false,
            'description': "If checked, will automatically deploy the latest version of the application with the terraform variable ${path.terraformVar}"
        ]
    }

    properties([
        parameters([
            // customParams _must_ come first to override the defaultParams
            utils.buildParams(customParams + defaultParams + applicationSourceParams),
            utils.buildParams(terraformVars, 'terraform'),
        ].collectMany { l -> l })
    ])

    pipeline {
        agent { label 'docker' }
        environment {
            REGISTRY_URL = '354979567826.dkr.ecr.us-east-1.amazonaws.com'
            STATIC_CONTEXT = "/tmp/test_workspace/${JOB_NAME}/${BUILD_NUMBER}"
        }
        stages {
            stage('Build workspace') {
                steps {
                    script {
                        workspace.build()
                        if (fileExists(params.PROPERTY_FILE_PATH)) {
                            PROPERTIES = readJSON file: params.PROPERTY_FILE_PATH
                        } else {
                            error("Properties file (${params.PROPERTY_FILE_PATH}) does not exist!")
                        }
                        CELL_FULL_NAME = "${PROPERTIES.cellName}-${PROPERTIES.componentName}"
                        COMPONENT_TYPE = PROPERTIES.componentName.split('-')[-1]
                        terraformParams = params.findAll { param -> param.key.matches('terraform-(.*)') }
                        applicationSourceBuiltParams = params.findAll { it.key.matches('(.*)_DEPLOY_LATEST') }
                    }
                }
            }
            stage('Deploy application') {
                steps {
                    script {
                        VERSION = deployApplication(
                            params.ENVIRONMENT,
                            COMPONENT_TYPE,
                            params.DEPLOY_LATEST,
                            CELL_FULL_NAME,
                            PROPERTIES.codeType,
                            params.AUTO_APPROVE,
                            terraformParams,
                            applicationSource,
                            applicationSourceBuiltParams,
                            PROPERTIES.sourceBucketName
                        )
                    }
                }
            }
        }
        post {
            success {
                script {
                    buildStatusMessage = """
                        Application deployed to ${params.ENVIRONMENT}.
                        Version: ${VERSION}
                    """.stripIndent()

                    String testsJobName = "${CELL_FULL_NAME}-cuke-tests-automation/${URLEncoder.encode(params.TESTS_BRANCH, 'UTF-8')}"
                    if (ENVIRONMENT.toLowerCase() != 'prod' && jenkins.model.Jenkins.instance.getItemByFullName(testsJobName) != null) {
                        build job: testsJobName,  
                        parameters: [string(name: 'ENVIRONMENT', value: params.ENVIRONMENT)]
                    } else {
                        echo 'success'
                    }
                }
            }
            failure {
                script {
                    buildStatusMessage = "Deploy to ${params.ENVIRONMENT} failed!"
                    slackSend channel: '#general', color: 'Good', message: 'Your build was unseccusful'
                }
            }
            aborted {
                script {
                    buildStatusMessage = "Deploy to ${params.ENVIRONMENT} aborted."
                }
            }
            cleanup {
                script {
                    try {
                        workspace.tearDown(CELL_FULL_NAME, false, false)
                    } catch (Exception e) {
                        echo 'An exception occurred while tearing down the workspace:'
                        echo e.getMessage()
                    }
                    try {
                        metrics.publishCloudwatchCdMetrics(params.ENVIRONMENT)
                    } catch (Exception e) {
                        echo 'An exception occurred while publishing Cloudwatch Metrics:'
                        echo e.getMessage()
                    }
                    notification.slack(
                        buildStatusMessage,
                        PROPERTIES.slack == null ? [] : PROPERTIES.slack as ArrayList<Map>
                    )
                }
            }
        }
    }
}
