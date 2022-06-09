   import java.net.URLEncoder

void call(List dockerBuildArgs=[], List customParams=[], Map dynamicSteps=[:]) {
    String buildStatusMessage = ''
    Map PROPERTIES
    String CELL_FULL_NAME
    String BASE_BRANCH = 'master'
    String COMPONENT_TYPE
    String DEPLOY_ENVIRONMENT
    Map userDockerBuildArgs
    String sonarScanResults = null
    List defaultParams = [
        ['name': 'PROPERTY_FILE_PATH', 'defaultValue': 'pipeline.json', 'description': 'Path to the pipeline.json file in your repository'],
        ['name': 'ENVIRONMENT', 'type': 'choice', 'choices': ['', 'SBX', 'DEV', 'TEST', 'PROD'], 'description': 'Triggers a deploy to the chosen environment. Leave blank to not trigger deploy to an environment. If you choose PROD the CD pipeline will have to be manually approved.'],
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
        agent { label 'ec2-agent' }
        environment {
            REGISTRY_URL = '354979567826.dkr.ecr.us-east-1.amazonaws.com'
            STATIC_CONTEXT = "/tmp/test_workspace/${JOB_NAME}/${BUILD_NUMBER}"
        }
def call(String repoUrl='', List customParams=[],  Map dynamicSteps=[:]) {
    
    pipeline {
        agent any
        tools {
        terraform 'terraform'
        }
        parameters { 
        choice(name: 'ENVIRONMENT', choices: ['', 'prod', 'sbx', 'dev'], description: "SELECT THE ACCOUNT YOU'D LIKE TO DEPLOY TO.")
        choice(name: 'ACTION', choices: ['', 'plan-apply', 'destroy'], description: 'Select action, BECAREFUL IF YOU SELECT DESTROY TO PROD')
        }
        stages{
            stage('Git checkout') {
            steps{
                   git branch: 'master',
                       url: "${repoUrl}"
                    sh """
                        pwd
                        ls -l
                    """
                }
            }
            stage('TerraformInit'){
                steps {
                        sh """
                            rm -rf .terraform 
                            terraform init -upgrade=true
                            echo \$PWD
                            whoami
                        """
                }
            }
        stage('Create Terraform workspace'){
                steps {
                        script {
                            try {
                                sh "terraform workspace select ${params.ENVIRONMENT}"
                            } catch (Exception e) {
                                echo "Error occurred: ${e.getMessage()}"
                                sh """
                                    terraform workspace new ${params.ENVIRONMENT}
                                    terraform workspace select ${params.ENVIRONMENT}
                                """
                            }
                
                        }
            }
        }
            stage('Terraform plan'){
                steps {
                        script {
                            try{
                                sh "terraform plan -var-file='${params.ENVIRONMENT}.tfvars' -refresh=true -lock=false -no-color -out='${params.ENVIRONMENT}.plan'"
                            } catch (Exception e){
                                echo "Error occurred while running terraform plan"
                                echo e.getMessage()
                                sh "terraform plan -refresh=true -lock=false -no-color -out='${params.ENVIRONMENT}.plan'"
                            }
                        }
                
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
                        script {
                            try {
                                sh """
                                    echo "llego" + params.ACTION
                                    terraform destroy -var-file=${params.ENVIRONMENT}.tfvars -no-color -auto-approve
                                """
                            } catch (Exception e){
                                echo "Error occurred"
                                echo e.getMessage()
                                sh "terraform destroy -no-color -auto-approve"
                            }
                        }
                        
                }else {
                            sh"""
                                echo  "llego" + params.ACTION
                                terraform apply ${params.ENVIRONMENT}.plan
                            """ 
                    }  // if

                }
                } //steps
            }  //stage
    }
    post {

        success {
        slackSend botUser: true, channel: 'jenkins_notification', color: 'good',
        message: "${params.ACTION} with ${currentBuild.fullDisplayName} completed successfully.\nMore info ${env.BUILD_URL}\nLogin to ${params.ENVIRONMENT} and confirm.", 
        teamDomain: 'slack', tokenCredentialId: 'slack'
        }
        failure {
        slackSend botUser: true, channel: 'jenkins_notification', color: 'danger',
        message: "Your Terraform ${params.ACTION} with ${currentBuild.fullDisplayName} got failed.", 
        teamDomain: 'slack', tokenCredentialId: 'slack'
        }
        aborted {
        slackSend botUser: true, channel: 'jenkins_notification', color: 'hex',
        message: "Your Terraform ${params.ACTION} with ${currentBuild.fullDisplayName} got aborted.\nMore Info ${env.BUILD_URL}", 
        teamDomain: 'slack', tokenCredentialId: 'slack'
        }
        cleanup {
        cleanWs()
        }

    }
    }
}    