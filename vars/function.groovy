void upload(String cellFullName, String dockerImageTag, String version, String codeType, String sourceBucketName, Boolean sharedFunctionBucket) {
    // Backwards compatible default.
    codeType = codeType == null ? 'go' : codeType
    sourceBucketName = sourceBucketName == null ? getSourceBucket(codeType, cellFullName) : sourceBucketName

    switch (codeType) {
        case ['go']:
            sh """
                docker create --name ${cellFullName} ${dockerImageTag}
                docker cp ${cellFullName}:/go/src/qnetgit.cms.gov/Bellese/${cellFullName}/${version}.zip .
            """
            String objectPath = sharedFunctionBucket ? "/${cellFullName}/" : ''
            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'hqr-prod-aws-access-key']]) {
                sh "aws s3 cp ${version}.zip s3://${sourceBucketName}${objectPath}"
            }
            break
        case ['python']:
            sh """
                docker run --name ${cellFullName} ${dockerImageTag}
                docker cp ${cellFullName}:/working/lambda.zip ${cellFullName}-v${version}.zip
                docker rm ${cellFullName}
            """
            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'hqr-prod-aws-access-key']]) {
                sh "aws s3 cp ${cellFullName}-v${version}.zip s3://${sourceBucketName}/${cellFullName}-v${version}.zip"
            }
            break
        case ['java']:
            sh """
                docker create --name ${cellFullName} ${dockerImageTag}
                docker cp ${cellFullName}:/working/target/${cellFullName}-${version}.jar .
                docker rm ${cellFullName}
            """
            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'hqr-prod-aws-access-key']]) {
                sh "aws s3 cp ${cellFullName}-${version}.jar s3://${sourceBucketName}/${cellFullName}/${version}.jar"
            }
            break
        default:
            error("Unsupported Lambda language: ${codeType}")
            break
    }
}

List getFileVersions(String bucketName) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'hqr-prod-aws-access-key']]) {
        return sh(
            script: """
                sudo aws s3 ls s3://${bucketName} \
                | sort -r \
                | awk '{print \$4}'
            """,
            returnStdout: true,
        ).split()
    }
}

String getSourceBucket(String codeType, String cellFullName) {
    String sourceBucketName
    String[] cellFullNameSplit = cellFullName.split('-')
    String newBucketName = String.format(
        'fn.src.%s.%s.%s',
        cellFullNameSplit[0],
        cellFullNameSplit[0..1].join('-'),
        cellFullNameSplit[2..cellFullNameSplit.length - 2].join('-')
    )

    switch (codeType) {
        case ['go']:
            Boolean legacyBucketExists = sh(
                script: "aws s3 ls 's3://${cellFullName}' 2>&1 | grep -q 'NoSuchBucket'",
                returnStatus:true
            ) != 0
            sourceBucketName = legacyBucketExists ? cellFullName : newBucketName
            break
        default:
            sourceBucketName = newBucketName
    }
    return sourceBucketName
}

