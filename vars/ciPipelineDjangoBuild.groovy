def call() {
   pipeline {

  agent any
    parameters { 
    
        string(name: 'REPO_NAME', description: 'PROVIDER THE NAME OF DOCKERHUB IMAGE', defaultValue: 'kojitechs-kart',  trim: true)
        string(name: 'REPO_URL', description: 'PROVIDER THE NAME OF DOCKERHUB/ECR URL', defaultValue: '674293488770.dkr.ecr.us-east-1.amazonaws.com',  trim: true)
        string(name: 'AWS_REGION', description: 'AWS REGION', defaultValue: 'us-east-1')
        choice(name: 'ACTION', choices: ['release', 'release', 'do-not-deploy'], description: 'Select action, would you like to release a new version?')
    }
    environment {
        tag = sh(returnStdout: true, script: "git rev-parse --short=10 HEAD").trim()
        REGISTRY_URL = '674293488770.dkr.ecr.us-east-1.amazonaws.com'
        STATIC_CONTEXT = "/tmp/test_workspace/${JOB_NAME}/${BUILD_NUMBER}"
    }
    stages {    
        stage('Build Workspace') {
                steps {
                    script {
                        workspace.build()
                        if (fileExists(params.REPO_NAME)) {
                            echo 'Created worksace'
                        } else {
                            error("Properties file (${params.REPO_NAME}) does not exist!")
                        }
                    }
                }
            }  
        stage('Docker Build Image') {
            steps {
                script {         
                    try {
                        sh"""
                            pwd && ls -al
                            docker build --compress -t kojitechs-kart .
                        """ 
                    }catch (Exception e) {
                        echo 'An exception occurred while Building image'
                        echo e.getMessage()
                    }
                }
            }
        }
        stage('Testing Docker Image') {
            steps {
                echo "Appending database secrets to file"
                script {         
                    try {
                        sh '''#!/bin/bash 
                            cat << EOF >> web/.env
                            POSTGRES_DB=dockerdc
                            POSTGRES_PASSWORD=mysecretpassword
                            POSTGRES_USER=myuser
                            POSTGRES_HOST=postgres_db
                            POSTGRES_PORT=5433
                            REDIS_HOST=redis_db
                            REDIS_PORT=6379 
                        EOF'''
                        sh"""python -m venv venv && source venv/bin/activate
                        docker-compose run --rm kojitechs-kart sh -c 'python manage.py wait_for_db && python manage.py test'
                        deactivate
                        """  
                    }catch (Exception e) {
                        echo 'An exception occurred while Testing image'
                        echo e.getMessage()
                }
                }
            }
        }
        stage('Confirm your action') {
                steps {
                    script {
                        timeout(time: 1, unit: 'MINUTES') {
                        def userInput = input(id: 'confirm', message: params.ACTION + '?', parameters: [ [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Release image version?', name: 'confirm'] ])
                    }
                }
            }  
        }
        stage('Release latest image version to ECR') {
            steps {
                sh 'echo "continue"'
                script{  
                    withAWS(roleAccount:'674293488770', role:'Role_For-S3_Creation') {
                    if (params.ACTION == "release"){
                        script {
                            try {
                                sh """
                                    aws ecr get-login-password  --region ${params.AWS_REGION} | docker login --username AWS --password-stdin ${params.REPO_URL}                 
                                    docker tag kojitechs-kart:latest ${params.REPO_URL}/${env.REGISTRY_URL}:${tag}
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
                    }  // if
                    }
                }
                    } //steps
            }
        }  //stage
    post {
        always {
            script {
                try {
                    sh'''
                    docker rm -f $(docker ps -aq)
                    docker rmi $(docker images -q)
                    '''
                } catch (Exception e) {
                    echo 'An exception occurred while pushing image to docker hub'
                    echo e.getMessage()
                }    
            }    
        }
        success {
            slackSend botUser: true, channel: 'jenkins_notification', color: 'good',
            message: " with ${currentBuild.fullDisplayName} completed successfully.\nMore info ${env.BUILD_URL}\nLogin to ${params.ENVIRONMENT} and confirm.", 
            teamDomain: 'slack', tokenCredentialId: 'slack'
        }
        failure {
            slackSend botUser: true, channel: 'jenkins_notification', color: 'danger',
            message: "${currentBuild.fullDisplayName} got failed.", 
            teamDomain: 'slack', tokenCredentialId: 'slack'
        }
        aborted {
            slackSend botUser: true, channel: 'jenkins_notification', color: 'hex',
            message: "Pipeline aborted due to a quality gate failure ${currentBuild.fullDisplayName} got aborted.\nMore Info ${env.BUILD_URL}", 
            teamDomain: 'slack', tokenCredentialId: 'slack'
        }
        cleanup {
            cleanWs()
        }
    } 
}       

}    
