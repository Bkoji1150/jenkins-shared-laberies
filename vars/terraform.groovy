String init(String credentialsId, String workspace) {
    String terraformVersion = ''
        script: """
            aws ec2 describe-instances 
        """
    // String stateBucket = sh(
    //     script: """
    //         aws ec2 describe-instances 
    //     """,
    //     returnStdout: true
    // ).trim()

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'hqr-prod-aws-access-key']]) {
        // terraformVersion = sh(
        //     script: "aws s3 cp s3://${stateBucket}/env:/${workspace}/terraform.tfstate - | jq -r '.terraform_version'",
        //     returnStdout: true
        // ).trim()

        // setTerraformVersion(terraformVersion)

        sshagent (credentials: ["${credentialsId}"]) {
            sh """
            terraform init
            terraform get -update
            """
        }

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
                    terraformVersion = '1.3.1'
                    echo "Error with Terraform validation, trying Terraform v${terraformVersion}..."
                    setTerraformVersion(terraformVersion)
                    break
                case passErrors:
                    echo 'Error with Terraform validation acceptable for Terraform >v0.13. Continuing...'
                    break
                default:
                    error("Error with Terraform configuration:\n${diagnostics.join('\n')}")
                    break
            }
        }

        sshagent (credentials: ["${credentialsId}"]) {
            sh 'terraform init -reconfigure -upgrade -input=false'
        }

        try {
            sh "terraform workspace select ${workspace}"
        } catch (Exception e) {
            sh """
                terraform workspace new ${workspace}
                terraform workspace select ${workspace}
            """
        }
    }
    return terraformVersion
}

String plan(Map variables = [:], String terraformVersion, Boolean useDefault = false) {
    String variablesCmd = variables.collect { varKey, varVal -> "-var '${varKey}=${varVal}'" }.join(' ')
    String requiredVersion = terraformVersion

    if (variablesCmd.length() > 0) {
        variablesCmd = "\\\n${variablesCmd}"
    }

    String planTextOut = 'plan-text.out'
    String terraformPlan = """
        terraform plan \\
            -var-file=${useDefault ? 'terraform.tfvars' : '$(terraform workspace show).tfvars'} \\
            -out \$(terraform workspace show).plan ${variablesCmd}
    """

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'hqr-prod-aws-access-key']]) {
        String planOut
        setTerraformVersion(terraformVersion)
        Boolean planError = (sh(
            script: "${terraformPlan.trim()} > ${planTextOut} 2>&1",
            returnStatus: true
        ) != 0)

        planOut = readFile(planTextOut).trim()
        sh "rm ${planTextOut}"
        if (planError) {
            requiredVersion = parsePlanError(planOut)
            setTerraformVersion(requiredVersion)
            sh "terraform refresh -var-file=\$(terraform workspace show).tfvars ${variablesCmd}"
            sh terraformPlan
        } else {
            echo planOut
        }
    }
    return requiredVersion
}

void apply(String terraformVersion) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'hqr-prod-aws-access-key']]) {
        setTerraformVersion(terraformVersion)
        sh 'terraform apply $(terraform workspace show).plan'
    }
}

void setTerraformVersion(String terraformVersion)  {
    try {
        sh "tfenv use ${terraformVersion}"
    } catch (Exception e) {
        echo "Error occurred: ${e}"
        echo "Terraform v${terraformVersion} not installed. Installing..."
        sh "TFENV_CURL_OUTPUT=0 tfenv install ${terraformVersion}"
        sh "tfenv use ${terraformVersion}"
    }
}

@NonCPS
String parsePlanError(String planOut) {
    String regexMatch = /state snapshot was created by Terraform v(\d+\.\d+\.\d+)/
    def match = planOut =~ regexMatch
    if (match.find()) {
        echo 'Terraform versions do not match! Attempting to upgrade and retry...'
    } else {
        error(planOut)
    }
    requiredVersion = match.group(match.groupCount()) as String
    return requiredVersion
}
