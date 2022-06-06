// jenkinsForJava.groovy
def call(String repoUrl) {
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
           stage('clean workspaces -----------') { 
            steps {
              cleanWs()
              sh 'env'
            } //steps
        }  //stage
           stage("Checkout Code") {
               steps {
                   git branch: 'master',
                       url: "${repoUrl}"
               }
           }
           stage("Cleaning workspace") {
               steps {
                   sh "mvn clean package -DskipTests=true"
               }
           }
           stage("Running Testcase") {
              steps {
                   sh "mvn test"
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



