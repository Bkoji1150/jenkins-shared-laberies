// Define variable
vars
| --- welcomeJob.groovy
| --- jenkinsForJava.groovy
 
// jenkinsForJava.groovy
def account = params.ENVIRONMENT
pipeline {
    agent any
    tools {
       terraform 'terraform'
    }
    parameters { 
      choice(name: 'ENVIRONMENT', choices: ['', 'prod', 'sbx', 'dev'], description: "SELECT THE ACCOUNT YOU'D LIKE TO DEPLOY TO.")
      choice(name: 'ACTION', choices: ['', 'plan-apply', 'destroy'], description: 'Select action, BECAREFUL IF YOU SELECT DESTROY TO PROD')
      string(name: 'GitHubRepository', description: "Please provide the github repository you'd like to deploy to.", trim: true)
    }
     stages{
        
        stage('clean workspaces -----------') { 
            steps {
              cleanWs()
              sh 'env'
            } //steps
        }  //stage

        stage('Git checkout') {
           steps{
                git params.GitHubRepository
                sh 'pwd' 
                sh 'ls -l'
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
                sh "terraform workspace select ${account}"
            }
        }
        stage('terraform plan') {
            steps{
                sh "terraform plan -var-file='${account}.tfvars' -refresh=true -lock=false -no-color  -out='${account}.plan' "
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
                         sh "terraform destroy -var-file=${account}.tfvars -no-color -auto-approve"
                } else {
                         sh ' echo  "llego" + params.ACTION'                 
                         sh "terraform apply ${account}.plan"
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
