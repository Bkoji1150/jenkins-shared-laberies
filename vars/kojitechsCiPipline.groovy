// jenkinsForJava.groovy
def call() {
  pipeline {
       agent any
       tools {
           maven 'mvn'
           jdk 'jdk'
       }
       stages {
           stage("Tools initialization") {
               steps {
                   sh "mvn --version"
                   sh "java -version"
               }
           }
           stage("Checkout Code") {
               steps {
                checkout scm
            }
           }
           stage('Compile') {
            steps {
                sh 'mvn clean'
            }
        }
             stage('Unit Tests Execution') {
            steps {
                sh 'mvn surefire:test'
            }
        }
           stage("Static Code analysis With SonarQube") {                                               
            steps {
              withSonarQubeEnv(installationName: 'sonar') {
                sh  'mvn sonar:sonar'
              }
            }
          }
          stage ("Waiting for Quality Gate Result") {
              steps {
                  timeout(time: 3, unit: 'MINUTES') {
                  waitForQualityGate abortPipeline: true 
              }
              }
          }

       }  
  }       
    post {
    success {
      slackSend botUser: true, channel: 'jenkins_notification', color: 'good',
      message: "The pipeline ${currentBuild.fullDisplayName} completed successfully.\nMore info ${env.BUILD_URL}", 
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



