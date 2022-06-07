
import java.net.URLEncoder

void call(List dockerBuildArgs=[], List customParams=[], Map dynamicSteps=[:]) {
    String buildStatusMessage = ''
    String BASE_BRANCH = 'master'
    Map PROPERTIES
    String ENVIRONMENT
    List defaultParams = [
        ['type': 'string', 'name': 'PROPERTY_FILE_PATH', 'defaultValue': 'Jenkinsfile', 'description': 'Path to the pipeline.json file in your repository'],
        ['name': 'ENVIRONMENT', 'type': 'choice', 'choices': ['', 'SBX', 'DEV', 'TEST', 'PROD'], 'description': 'Triggers a deploy to the chosen environment. Leave blank to not trigger deploy to an environment. If you choose PROD the CD pipeline will have to be manually approved.'],
        ['name': 'TESTS_BRANCH', 'defaultValue': 'master', 'description': 'Specify the branch to trigger on the corresponding Test Pipeline. This parameter can be ignored if a Test Pipeline does not exist.'],
    ]

    pipeline {
        agent any
       
        tools {
        terraform 'terraform'
        }

        stages{
               stage('Build workspace') {
                steps {
                    script {
                        workspace.build()
                        if (fileExists(params.PROPERTY_FILE_PATH)) {
                            PROPERTIES = readJSON file: params.PROPERTY_FILE_PATH
                        } else {
                            error("Properties file (${params.PROPERTY_FILE_PATH}) does not exist!")
                        }
                    }
                }
            }
            stage('terraform Init') {
                steps{
                    sh 'terraform init'
                    sh 'terraform --version'
                    sh 'ls -la'
                }
            }
            stage('terraform create workspace') {
                steps{
                    sh "terraform workspace select ${params.DEPLOY_ENVIRONMENT}"
                }
            }
            stage('terraform plan') {
                steps{
                    sh "terraform plan -var-file='${params.DEPLOY_ENVIRONMENT}.tfvars' -refresh=true -lock=false -no-color  -out='${account}.plan' "
                }
            }
            stage('Confirm your action') {
                steps {
                    script {
                        def userInput = input(id: 'confirm', message: params.ACTION + '?', parameters: [ [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Apply terraform', name: 'confirm'] ])
                    }
                }
            }
        stage('Terraform apply or destroy ----------------') {
                steps {
                sh 'echo "continue"'
                script{  
                    if (params.ACTION == "destroy"){
                            sh 'echo "llego" + params.ACTION'   
                            sh "terraform destroy -var-file=${params.DEPLOY_ENVIRONMENT}.tfvars -no-color -auto-approve"
                    } else {
                            sh ' echo  "llego" + params.ACTION'                 
                            sh "terraform apply ${params.DEPLOY_ENVIRONMENT}.plan"
                    }  // if

                }
                } //steps
            }  //stage
    }
    post {

        success {
        slackSend botUser: true, channel: 'jenkins_notification', color: 'good',
        message: "The pipeline ${currentBuild.fullDisplayName} completed successfully.\nMore info ${env.BUILD_URL}\nLogin to ${account} and confirm.", 
        teamDomain: 'slack', tokenCredentialId: 'slack'
        }
        failure {
        slackSend botUser: true, channel: 'jenkins_notification', color: 'danger',
        message: "The pipeline ${currentBuild.fullDisplayName} completed with failure.\nError in ${env.BUILD_URL}", 
        teamDomain: 'slack', tokenCredentialId: 'slack'
        }
        cleanup {
        cleanWs()
        }

    }
    }
}
