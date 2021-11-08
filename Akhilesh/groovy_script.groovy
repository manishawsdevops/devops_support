#!groovy

@Grab('io.jsonwebtoken:jjwt:0.9.0')
import groovy.json.JsonSlurper
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.SignatureException
import groovy.json.JsonSlurperClassic
import hudson.FilePath
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Default to pmt-env-ms (points to the microservice related to us-east)
// pmt-env-ms-g to work with GlovCloud
msForInstance = "pmt-env-ms"

//global variables block
//program parameter passed from jenkins
packerBranch = params.PACKER_BRANCH
instanceNames = params.EC2_INSTANCE_NAMES
instanceRegion = params.AWS_REGION
purpose = params.PURPOSE
crossCheckCommitTlaLabels = params.CROSS_CHECK_COMMIT
buildCommit = ""
primaryTla = ""
updateLabel = ""
buildLabel = ""
buildUserMail = null 

// Global Map - Please, don't change the signature
tagMaps = [:]
awsAccessKey = ""
awsSecretKey = ""
appusrKey =  ""
centosKey =  ""
opsdashJwt =  ""
slackToken = ""

sitInProcess = "";
authToken = "";
databaseVerDesired = '19';

failedStage = ""
failedSit = ""

sitMap = [:]

// Private IP from the Assigned SIT
privateEC2IP = ""
envName = ""
currentStage = ""

// Create as many containers/pods as Assigend SITS ( Those will take care of the SITs processes )
count = 0
intanceNamesArr = instanceNames.split(",")

isEmpty = {String str -> str == null || str =~ /^[ ]*$/};

// ---------------------------------- Common methods for Master --------------------------------

def slackResults(message) {
  String[] user = buildUserMail.split('@')
  String owner = "@" + user[0];
    println("[Slack Results] User..........: " + user[0])
    println("[Slack Results] owner..........: " + owner)
    sh script: """
      curl -X POST -H "Authorization: Bearer ${slackToken}" -H "Content-type: application/json" --data '{\"channel\":\"${owner}\",\"username\":\"DevOps\",\"text\":\":memo: ${message}\"}' https://slack.com/api/chat.postMessage
    """, returnStdout: true
}

def slackSITResults(hostname, errorMessage, stage) {

  buildUserMail = "maguiar@paymentus.com";
  String[] user = buildUserMail.split('@')
  String owner = "@" + user[0];
    println("[Slack Results] User..........: " + user[0])
    println("[Slack Results] hostname..........: " + hostname)
    println("[Slack Results] owner..........: " + owner)
    println("[Slack Results] stage..........: " + stage)
    
    sh script: """
      set -e +x
      curl -X POST -H "Authorization: Bearer ${slackToken}" -H "Content-type: application/json" --data '{\"channel\":\"${owner}\",\"username\":\"DevOps\",\"text\":\":rotating_light: The SIT ${hostname} failed during his update! Follow the details...: ${errorMessage}\"}' https://slack.com/api/chat.postMessage
    """, returnStdout: true
}

// Do not use this method to replace spots where we expose the SysDBA Password
def appusrRemoteCall(privateEC2IP, pcommand, psleep) {
  String command = pcommand;
  String bashStatusOutput = "";
  
  try {
    // https://www.jenkins.io/doc/pipeline/steps/workflow-durable-task-step/#sh-shell-script
    bashStatusOutput=sh(returnStatus: true, script: "ssh -o StrictHostKeyChecking=no -i /home/jenkins/.ssh/provision-key appusr@${privateEC2IP} '${command}'");
    println("...[appusr remote call for "+ envName +"] - Executing Command...: " + command + " - Return Status...: " + bashStatusOutput);
    sh script: """
      sleep ${psleep}
    """, returnStdout: true
    
  } catch(err) {
    String failedMessage = "[appusr remote call] Error Message for (" + envName + ")-[ " + command + " ]-[ " + err.getMessage() + " ]- At Stage : [ " + currentStage + " ]"
    slackSITResults envName,failedMessage, "[Executing Remote Appusr Call]"
    echo err.getMessage()
  }
}

def getTagMaps(reservations) {
		def returnTagMap = [:];
		//collect tags in an easily readable map
		for (reservation in reservations) {
			localTags = [:]
			for (tag in reservation.Instances[0].Tags){
				localTags[tag.Key]=tag.Value
			}
			returnTagMap[reservation.Instances[0].InstanceId] = localTags;
		}
		return returnTagMap;
}

def Object sendHttpGetRequest(url, headers){

  echo "sending http GET: to ${url}";
  
  def response = httpRequest(consoleLogResponseBody: true,
    contentType: 'APPLICATION_JSON',
    customHeaders: headers,
    httpMode: 'GET',
    ignoreSslErrors: true,
    quiet: true,
    timeout: 0,
    url: url)
  return readJSON(text: response.content)
}

def Object sendHttpPostRequest(url, headers, payload, ptimeout){
  echo "sending http Post: to ${url}";
  
  def response = httpRequest(consoleLogResponseBody: true,
    contentType: 'APPLICATION_JSON',
    customHeaders: headers,
    httpMode: 'POST',
    ignoreSslErrors: true,
    requestBody: payload,
    responseHandle: 'NONE',
    timeout: ptimeout,
    url: url)

  return readJSON(text: response.content)
}

// Token will expire in 2 hours
def generateAuthToken(secretBase64) {
  Date expDate = new Date()
  long exp = expDate.getTime() + 1000 * 7200

  Map<String, Object> headers = new HashMap<>()
  headers.put("typ", "JWT")

  Map<String, Object> tokenData = new HashMap<>()
  tokenData.put("exp", exp)
  tokenData.put("email", env.BUILD_USER_EMAIL)
  tokenData.put("user", env.BUILD_USER_EMAIL)

  return Jwts.builder().setHeaderParams(headers).setClaims(tokenData).signWith(SignatureAlgorithm.HS256, secretBase64).compact()
}

def Map getVaultSecrets(vaultUrl){
  
  echo "Getting Vault Secrets for URL:${vaultUrl}"
  def secrets = [:]

  withCredentials([string(credentialsId: 'vault-token', variable: 'vaultTokenCred')]) {
     Map respMap = sendHttpGetRequest(vaultUrl,
    [[maskValue: true, name: 'X-Vault-Token', value: "$vaultTokenCred"]]).data
    respMap.each { key, value ->
       secrets[key] = value;
    }
  }
  return secrets
}

def addPvtKeyToJenkinsUser(keyFileName,keyTextValue){
  writeFile file: keyFileName, text: keyTextValue
  output = sh script: """
    chmod 600 ${keyFileName}
  """, returnStdout: false
}

def delPvtKeyFromJenkinsUser(keyFileName){
  new File(keyFileName).delete() 
}

def isCommitAbsentOnBranch(requiredHashCode, branch, gitFolder ){
  if (isEmpty(requiredHashCode)){
    echo "Required Hash Code is empty"  
    return false
  }
  echo "Required Hash Code is not empty: ${requiredHashCode}"  
  def commitPresent = sh(returnStatus: true, script: "cd ${gitFolder} && git branch --contains ${requiredHashCode} --points-at origin/${branch}");
  //rebaseRequired is 0 when the commit is found. So for 0 commitPresent would be false. 
  return (commitPresent == 0)?false:true
}

node('sit-stateful') {
  wrap([$class: 'BuildUser']) {
  echo "Build Start TimeStamp: ${new Date()}"
  echo "Starting job with the following params"
  echo "instanceNames = ${instanceNames}"
  echo "instanceRegion = ${instanceRegion}"
  
  try{
  //get secrets from vault
  def sitSecrets = getVaultSecrets("https://consul.paymentus.io:8501/v1/secret/sit-provisioning-settings-us-east")
  awsAccessKey = sitSecrets['aws-access-key']
  awsSecretKey = sitSecrets['aws-secret-key']
  appusrKey = sitSecrets["appusr-p-key"]
  opsdashJwt = sitSecrets["ops-dash-ms-jwt-base64"]
  centosKey = sitSecrets["centos-p-key"]
  slackToken = sitSecrets['slack-token']
  buildUserMail = env.BUILD_USER_EMAIL;

        sh script: """
               set -e +x
               mkdir -p /home/jenkins/.ssh
               cat > /home/jenkins/.ssh/provision-key <<EOF\n$appusrKey\nEOF
               chmod 600 /home/jenkins/.ssh/provision-key
               if [ ! -d dev/Database/docker ];then
                mkdir -p dev/Database/docker
               fi
              """, returnStdout: false
      sh script: """
               set -e +x
               mkdir -p /home/jenkins/.ssh
               cat > /home/jenkins/.ssh/centos-provision-key <<EOF\n$centosKey\nEOF
               chmod 600 /home/jenkins/.ssh/centos-provision-key
              """, returnStdout: false

  // Override just the access keys, those are the only secrets we need for gov cloud SITs
  if(instanceRegion.equals("us-gov-west-1")) {
	//get secrets from vault ( --region us-gov-west-1 )
	echo "...Working with a Gov Cloud SIT!"
	def sitGovSecrets = getVaultSecrets("https://consul.paymentus.io:8501/v1/secret/sit-provision-settings")
	awsAccessKey = sitGovSecrets['aws-access-key']
	awsSecretKey = sitGovSecrets['aws-secret-key']
  }    

  //add the private keys to jenkins user
  addPvtKeyToJenkinsUser("provision-key",appusrKey)
  
  authToken = generateAuthToken(opsdashJwt)
    
  String[] user = buildUserMail.split('@')
  echo "Creating Env: ${user[0]}"
    currentBuild.displayName = "${user[0]}-fleetupdate"
    currentStage = 'FleetUpdateWithDBPatch'
    stage(currentStage) {
    
          stage('Checkout Packer') {
            echo "Checkout Packer Start TimeStamp: ${new Date()}"

            clone_packer: {
                dir('packer') {
                    if (!packerBranch?.trim()){
                        packerBranch="master"
                    }
                    echo "Checking out packer repo"
                    checkout([$class: 'GitSCM', branches: [[name: packerBranch]],
                    extensions: [[$class: 'CloneOption', timeout: 30, shallow: true, noTags:true],[$class: 'CleanBeforeCheckout']], gitTool: 'Default',
                    userRemoteConfigs: [[credentialsId: 'opsbuild-private-key', url: 'ssh://git@scm-ssh.paymentus.io:7999/devops/packer.git']]])
                }    
                //load the utils
                util = load("${WORKSPACE}/packer/jenkin-job/packer-util.groovy")
                slackNotfChannel = util.getSlackNotificationChannel()
            }
          }
    
          // Creating the container/pod that will take care of the CICD Server process
          currentStage = 'Get Instances Attributes'
          stage(currentStage) {
            echo "Getting instance details from aws for ${intanceNamesArr}"
            util.logmsg("Getting instance details from aws for ${intanceNamesArr}")

            withEnv(["AWS_ACCESS_KEY_ID=${awsAccessKey}", "AWS_SECRET_ACCESS_KEY=${awsSecretKey}"]) {
                  instanceAttrText= sh(
                    returnStdout: true,
                    script: "aws ec2 describe-instances --filter \"Name=tag:Name,Values=${instanceNames}\" --region ${instanceRegion} --output json"
                    ).trim()
                
                  instanceAttributes = readJSON (text:instanceAttrText)
            }

            if (instanceAttributes.Reservations.size() != intanceNamesArr.size()) {
              echo "Names found in aws are:"
              for (reservation in instanceAttributes.Reservations){
                echo "Instance ID:${reservation.Instances[0].ImageId}"
              }
              error ("Not all instances were found in AWS")
            }

            tagMaps = getTagMaps(instanceAttributes.Reservations);
            echo "Instance attributes are:\n ${instanceAttributes.Reservations[0].Instances[0].ImageId}"

          }
          
          currentStage = 'Validate Instances'
          stage(currentStage){

            String prevBuildCommit = ""
            String prevTla = ""
            String prevUpdateLabel= ""
            String prevBuildLabel = ""

            for (reservation in instanceAttributes.Reservations) {
              
              // echo "Collecting attributes specific to an instance for ${instanceAttributes.Reservations[0].Instances[0].InstanceId}"
              def tags = tagMaps[reservation.Instances[0].InstanceId];
              def instancePvtIp = reservation.Instances[0].PrivateIpAddress;
              
              // verify that all build commits are the same. otherwise one of the vms in fleet can
              // have issues with db patch etc.
              echo "Build Commit ${tags.BUILD_COMMIT}"
              if (!isEmpty(prevBuildCommit) && (prevBuildCommit != tags.BUILD_COMMIT) && crossCheckCommitTlaLabels){
				
                echo "The Build Commits of the SIT: ${tags.Name} does not match with other instances. This SIT is not eligible to be updated using Fleet. Please, manually update this SIT using the regular update process in order to move it up to the same level as other fleet members! Once updated to the same level as other fleet members it can participate in fleet update. Alternatively, you can remove this SIT from the Fleet List, and run the fleet update process without it."
              }
			  
              if (!isEmpty(prevUpdateLabel) && (prevUpdateLabel != tags.UpdateLabel)  && crossCheckCommitTlaLabels){
				
                echo "The Update Label of the SIT: ${tags.Name} does not match with other instances. This SIT is not eligible to be updated using Fleet. Please, manually update this SIT using the regular update process in order to move it up to the same level as other fleet members! Once updated to the same level as other fleet members it can participate in fleet update. Alternatively, you can remove this SIT from the Fleet List, and run the fleet update process without it."
              }

              // echo "Build Label ${tags.BuildLabel}"

              prevBuildCommit = tags.BUILD_COMMIT
              prevTla = tags.PrimaryTla
              prevUpdateLabel = tags.UpdateLabel
              prevBuildLabel = tags.BuildLabel

            }

            echo "Validate Instance End TimeStamp: ${new Date()}"
			echo "============================================================================"

          }


// ** Akhilesh
          currentStage = 'Validate Logged User'
          stage(currentStage){
            echo "Logged user...: ${env.BUILD_USER_EMAIL}"
            echo "Getting instance details from aws for ${intanceNamesArr}"
            util.logmsg("Getting instance details from aws for ${intanceNamesArr}")

            withEnv(["AWS_ACCESS_KEY_ID=${awsAccessKey}", "AWS_SECRET_ACCESS_KEY=${awsSecretKey}"]) {
              for ( everyinstance in intanceNamesArr) {
                  instanceTagsText= sh(
                    returnStdout: true,
                    script: "aws ec2 describe-tags --filter \"Name=tag:Name,Values=${everyinstance}\" --region ${instanceRegion} --output json"
                    ).trim()
                  instanceAttributes = readJSON (text:instanceTagsText)
                  println(instanceAttributes)
              }

            }
          }
      echo "============================================================================"
 
//* Changes existing
        //  stage('Validate the logged User') {
        //       def listOfAllowedUsers = [""]
        //       echo "Logged user...: ${env.BUILD_USER_EMAIL}"
              
        //       echo "============================================================================"
		    //   echo "[SIT Name]    [AllowedUsersToUpdate]"
        //       for (reservation in instanceAttributes.Reservations) {
              
        //         // echo "Collecting attributes specific to an instance for ${instanceAttributes.Reservations[0].Instances[0].InstanceId}"
        //         def tags = tagMaps[reservation.Instances[0].InstanceId];
			  //   echo "${tags.Name}    ${tags.AllowedUsersToUpdate}"
			  //   sitMap[tags.Name] = ["Name":tags.Name, "Tla":tags.AllowedUsersToUpdate]
			  //   listOfAllowedUsers = tags.AllowedUsersToUpdate
        //       }
              
        //       echo "List of allowed users to update this SIT...: ${listOfAllowedUsers}"
        //   }
//**

          currentStage = 'Docker Network'
          stage(currentStage){

            for (reservation in instanceAttributes.Reservations) {
              
              // echo "Collecting attributes specific to an instance for ${instanceAttributes.Reservations[0].Instances[0].InstanceId}"
              def tags = tagMaps[reservation.Instances[0].InstanceId];
              def instancePvtIp = reservation.Instances[0].PrivateIpAddress;
              
              envName = tags.Name
              
              echo "...Instance Private IP ${instancePvtIp} - Instance Name ${tags.Name}"

              localcommand = 'cd ~/paycfg/paymentus-stack/ && docker ps|wc -l'
              appusrRemoteCall instancePvtIp,localcommand,1
            }

            echo "Validating Docker Network End TimeStamp: ${new Date()}"
			echo "============================================================================"
          }

		  currentStage = 'Display Attributes'
          stage(currentStage){

			echo "============================================================================"
		    echo "[SIT Name]    [Primary TLA]   [Update Label]  [Build Commit]"
            for (reservation in instanceAttributes.Reservations) {
              
              // echo "Collecting attributes specific to an instance for ${instanceAttributes.Reservations[0].Instances[0].InstanceId}"
              def tags = tagMaps[reservation.Instances[0].InstanceId];
			  echo "${tags.Name}    ${tags.PrimaryTla}  ${tags.UpdateLabel} ${tags.BUILD_COMMIT}"
			  sitMap[tags.Name] = ["Name":tags.Name, "Tla":tags.PrimaryTla, "UpdateLabel":tags.UpdateLabel, "BuildCommit":tags.BUILD_COMMIT]
            }

            echo "Display Attributes End TimeStamp: ${new Date()}"
			echo "============================================================================"
            slackResults(sitMap.values())
          }
          
          currentStage = 'Timestamp'
          stage(currentStage) {
              def now = LocalDateTime.now();
              def currentDate = ""
		      currentDate = now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
		      println "...TimeStamp...: " + currentDate
		      
          }

    } // stage
  } catch(Exception ex) {
    echo ex.getMessage()
  }
  } // wrap
} // node POD_LABEL