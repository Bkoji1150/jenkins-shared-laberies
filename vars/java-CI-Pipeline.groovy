
pipeline {
    agent any
  tools {
    maven 'mvn'
  } 
  parameters { 
          string(name: 'REPO_NAME', description: 'PROVIDER THE NAME OF ECR REPO', defaultValue: 'ci-cd-demo-kojitechs-webapp',  trim: true)
          string(name: 'REPO_URL', description: 'PROVIDER THE NAME OF DOCKERHUB/ECR URL', defaultValue: '735972722491.dkr.ecr.us-east-1.amazonaws.com',  trim: true)
          string(name: 'AWS_REGION', description: 'AWS REGION', defaultValue: 'us-east-1')
          choice(name: 'ACTION', choices: ['RELEASE', 'RELEASE', 'NO'], description: 'Select action, BECAREFUL IF YOU SELECT DESTROY TO PROD')
    } 
  environment {
          tag = sh(returnStdout: true, script: "git rev-parse --short=10 HEAD").trim()
    }  
    stages {
        stage('mvn Compile and Build') {
          steps {
            sh"""mvn clean install package &&
                  mvn surefire:test
              """    
            }
        }
        stage('Static Code analysis with Sonarqube') {
            steps {
              withSonarQubeEnv(installationName: 'sonar') {
                sh 'mvn sonar:sonar'
              }
            }
        }
        stage('Waiting for quality Gate Result') {
            steps {
                timeout(time: 3, unit: 'MINUTES') {
                  waitForQualityGate abortPipeline: true
               }
            }
        }
        stage('Docker Build Image') {
            steps {      
                sh"""
                    pwd && ls -al
                    docker build -t ${params.REPO_NAME} .
                  """ 
              }
        }
        stage('Confirm your action') {
                steps {
                    script {
                        timeout(time: 5, unit: 'MINUTES') {
                        def userInput = input(id: 'confirm', message: params.ACTION + '?', parameters: [ [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Release image version?', name: 'confirm'] ])
                    }
                }
            }  
        } 
        stage('Release latest image version to ECR') {
              steps {
                  sh 'echo "continue"'
                  script{  
                      if (params.ACTION == "RELEASE"){
                          script {
                              try {
                                  sh """
                                      aws ecr get-login-password  --region ${params.AWS_REGION} | docker login --username AWS --password-stdin ${params.REPO_URL}                 
                                      docker tag ${params.REPO_NAME}:latest ${params.REPO_URL}/${params.REPO_NAME}:${tag}
                                      docker push ${params.REPO_URL}/${params.REPO_NAME}:${tag}
                                      docker tag ${params.REPO_URL}/${params.REPO_NAME}:${tag} ${params.REPO_URL}/${params.REPO_NAME}:latest
                                      docker push ${params.REPO_URL}/${params.REPO_NAME}:latest
                                  """
                              } catch (Exception e){
                                  echo "Error occurred: ${e}"
                                  sh "An eception occured"
                              }
                          }

                      }
                      else {
                              sh"""
                                  echo  "llego" + params.ACTION
                                  image release would not be deployed!"
                              """ 
                      } 
                  }
                      } 
              }
      }    
    post {
        success {
            slackSend botUser: true, channel: 'jenkins_notification', color: 'good',
            message: " with ${currentBuild.fullDisplayName} completed successfully.\nMore info ${env.BUILD_URL}\nLogin to ${params.ENVIRONMENT} and confirm.", 
            teamDomain: 'slack', tokenCredentialId: 'slack-token'
        }
        failure {
            slackSend botUser: true, channel: 'jenkins_notification', color: 'danger',
            message: "Build faild${currentBuild.fullDisplayName} failed.", 
            teamDomain: 'slack', tokenCredentialId: 'slack-token'
        }
        aborted {
            slackSend botUser: true, channel: 'jenkins_notification', color: 'hex',
            message: "Pipeline aborted due to a quality gate failure ${currentBuild.fullDisplayName} got aborted.\nMore Info ${env.BUILD_URL}", 
            teamDomain: 'slack', tokenCredentialId: 'slack-token'
        }
        cleanup {
            cleanWs()
        }
    }
}
