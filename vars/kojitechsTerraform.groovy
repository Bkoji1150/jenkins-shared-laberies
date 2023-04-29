def call() {

    pipeline {
        agent any
        parameters { 
            choice(name: 'ENVIRONMENT', choices: ['sbx', 'prod', 'sbx', 'shared'], description: "SELECT THE ACCOUNT YOU'D LIKE TO DEPLOY TO.")
            choice(name: 'ACTION', choices: ['apply', 'apply', 'destroy'], description: 'Select action, BECAREFUL IF YOU SELECT DESTROY TO PROD')
            choice(name: 'ANSIBLE_HOSTS', choices: ['', 'ubuntu', 'redhat', 'auto_scaling'], description: 'Select target HOSTS FILE to apply to to')
        }
        stages{    
            stage('terraform init') {
                steps{
                checkout scm
                    sh """
                        terraform init
                        terraform get -update
                    """
                }
            }
            stage('Terraform validation') {
                steps {   
                     script {
                        String validateTerraformOutput = sh(
                            script: 'terraform validate -json || true',
                            returnStdout: true
                        ).trim()
                        def validateTerraform = readJSON text: validateTerraformOutput
                        if (!validateTerraform.valid) {
                            List<String> diagnostics = validateTerraform.diagnostics*.summary.unique()
                            List<String> upgradeErrors = ['Custom variable validation is experimental']
                            List<String> passErrors = ['Could not load plugin']
                            if (diagnostics.size > 1) {
                                error("Error with Terraform configuration:\n${diagnostics.join('\n')}")
                            }
                            switch (diagnostics[0] as String) {
                                case upgradeErrors:
                                    echo "Error with Terraform validation, trying Terraform v${terraformVersion}..."
                                    break
                                case passErrors:
                                    echo 'Error with Terraform validation acceptable for Terraform >v0.13. Continuing...'
                                    break
                                default:
                                    error("Error with Terraform configuration:\n${diagnostics.join('\n')}")
                                    break
                            }
                        }   
                    }
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
                                sh "terraform plan  -var-file=\$(terraform workspace show).tfvars  -refresh=true -lock=false -no-color -out='${params.ENVIRONMENT}.plan'"
                            } catch (Exception e){
                                echo "Error occurred while running"
                                echo e.getMessage()
                                sh "terraform plan -refresh=true -lock=false -no-color -out='${params.ENVIRONMENT}.plan'"
                        }
                    }
                }
            }
            stage('Confirm your action') {
                steps {
                    script {
                        timeout(time: 5, unit: 'MINUTES') {
                        def userInput = input(id: 'confirm', message: params.ACTION + '?', parameters: [ [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Apply terraform', name: 'confirm'] ])
                    }
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
                                echo "Error occurred while running"
                                echo e.getMessage()
                                sh "terraform plan -refresh=true -lock=false -no-color -out='${params.ENVIRONMENT}.plan'"
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
        }
        stage('ansible-test') {
            steps{
                script{    
                if (params.ACTION == "apply" && params.ANSIBLE_HOSTS){
                    script {
                        try{      
                            sh"""
                                export ANSIBLE_CONFIG=./ansible/inventory/ansible.cfg
                                export ANSIBLE_LOG_PATH=./ansible/inventory/bootstrap.log 
                                cat ./ansible/inventory/ansible.cfg
                                /Library/Frameworks/Python.framework/Versions/3.10/bin/ansible-playbook ./ansible/playbook/ping_playbook.yaml -e hostname=${params.ANSIBLE_HOSTS}
                                cat ./ansible/inventory/bootstrap.log
                            """
                        }catch (Exception e){
                                echo e.getMessage()
                            }
                    }    
                }else {
                    echo "Pass"
                }
            }  // if
            }
        }
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
