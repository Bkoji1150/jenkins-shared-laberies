def call() {
      
    pipeline {
            agent any
        tools {
            terraform 'terraform'
        }
        parameters { 
            choice(name: 'ENVIRONMENT', choices: ['sbx', 'prod', 'sbx', 'shared'], description: "SELECT THE ACCOUNT YOU'D LIKE TO DEPLOY TO.")
            choice(name: 'ACTION', choices: ['apply', 'apply', 'destroy'], description: 'Select action, BECAREFUL IF YOU SELECT DESTROY TO PROD')
        }
        stages{    
            stage('Git checkout') {
                steps{
                    checkout scm
                        sh """
                            pwd
                            ls -l
                        """
                    }
            }
  
            stage('TerraformInit'){
                steps {
                      withAWS(roleAccount:'735972722491', role:'Role_For-S3_Creation') {           
                          sh """
                            rm -rf .terraform 
                            terraform init -upgrade=true
                            echo \$PWD
                            whoami
                        """
                   }
                }
            }
        stage('Create Terraform workspace'){
                steps {
                   withAWS(roleAccount:'735972722491', role:'Role_For-S3_Creation') {  
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
        }
            stage('Terraform plan'){
                steps {
                        script {
                           withAWS(roleAccount:'735972722491', role:'Role_For-S3_Creation') {     
                            try{
                                sh "terraform plan -var-file='${params.ENVIRONMENT}.tfvars' -refresh=true -lock=false -no-color -out='${params.ENVIRONMENT}.plan'"
                            } catch (Exception e){
                                echo "Error occurred while running"
                                echo e.getMessage()
                                sh "terraform plan -refresh=true -lock=false -no-color -out='${params.ENVIRONMENT}.plan'"
                            }
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
                    withAWS(roleAccount:'735972722491', role:'Role_For-S3_Creation') {       
                    if (params.ACTION == "destroy"){
                        script {
                            try {
                                sh """
                                    echo "llego" + params.ACTION
                                    terraform destroy -var-file=${params.ENVIRONMENT}.tfvars -no-color -auto-approve
                                """
                            } catch (Exception e){
                                echo "Error occurred: ${e}"
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
                }
                } //steps
            }  //stage
    }
    post {
        success {
            slackSend botUser: true, channel: 'jenkins_notification', color: 'good',
            message: "${params.ACTION} with ${currentBuild.fullDisplayName} completed successfully.\nMore info ${env.BUILD_URL}\nLogin to ${params.ENVIRONMENT} Account and confirm.", 
            teamDomain: 'slack', tokenCredentialId: 'slack'
        }
        failure {
            slackSend botUser: true, channel: 'jenkins_notification', color: 'danger',
            message: "Your Terraform ${params.ACTION} with ${currentBuild.fullDisplayName} got failed.\nPlease go to ${env.BUILD_URL} and verify the build", 
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
