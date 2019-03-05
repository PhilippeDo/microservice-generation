/*-
 * ===============LICENSE_START=======================================================
 * Acumos
 * ===================================================================================
 * Copyright (C) 2017 AT&T Intellectual Property & Tech Mahindra. All rights reserved.
 * ===================================================================================
 * This Acumos software file is distributed by AT&T and Tech Mahindra
 * under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * This file is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ===============LICENSE_END=========================================================
 */

package org.acumos.microservice.component.docker;

import java.time.Instant;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.annotation.PostConstruct;
import org.acumos.cds.CodeNameType;
import org.acumos.cds.client.CommonDataServiceRestClientImpl;
import org.acumos.cds.domain.MLPArtifact;
import org.acumos.cds.domain.MLPCodeNamePair;
import org.acumos.cds.domain.MLPSolution;
import org.acumos.cds.domain.MLPSolutionRevision;
import org.acumos.cds.domain.MLPUser;
import org.acumos.cds.domain.MLPTask;
import org.acumos.microservice.component.docker.cmd.CreateImageCommand;
import org.acumos.microservice.component.docker.cmd.DeleteImageCommand;
import org.acumos.microservice.component.docker.cmd.PushImageCommand;
import org.acumos.microservice.component.docker.cmd.TagImageCommand;
import org.acumos.microservice.component.docker.preparation.H2ODockerPreparator;
import org.acumos.microservice.component.docker.preparation.JavaGenericDockerPreparator;
import org.acumos.microservice.component.docker.preparation.PythonDockerPreprator;
import org.acumos.microservice.component.docker.preparation.RDockerPreparator;
import org.acumos.microservice.services.impl.DownloadModelArtifacts;
import org.acumos.nexus.client.NexusArtifactClient;
import org.acumos.nexus.client.RepositoryLocation;
import org.acumos.onboarding.common.exception.AcumosServiceException;
import org.acumos.onboarding.common.models.OnboardingNotification;
import org.acumos.onboarding.common.models.ServiceResponse;
import org.acumos.onboarding.common.utils.EELFLoggerDelegate;
import org.acumos.onboarding.common.utils.LogBean;
import org.acumos.onboarding.common.utils.LogThreadLocal;
import org.acumos.onboarding.common.utils.OnboardingConstants;
import org.acumos.onboarding.common.utils.ResourceUtils;
import org.acumos.onboarding.common.utils.UtilityFunction;
import org.acumos.onboarding.component.docker.preparation.Metadata;
import org.acumos.onboarding.component.docker.preparation.MetadataParser;
import org.acumos.onboarding.logging.OnboardingLogConstants;
import org.acumos.onboarding.services.impl.CommonOnboarding;
import org.acumos.onboarding.services.impl.PortalRestClientImpl;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.multipart.MultipartFile;

import com.github.dockerjava.api.DockerClient;

public class DockerizeModel {
	
	private static final EELFLoggerDelegate logger = EELFLoggerDelegate.getLogger(DockerizeModel.class);
	
	@Value("${nexus.nexusEndPointURL}")
	protected String nexusEndPointURL;

	@Value("${nexus.nexusUserName}")
	protected String nexusUserName;

	@Value("${nexus.nexusPassword}")
	protected String nexusPassword;

	@Value("${nexus.nexusGroupId}")
	protected String nexusGroupId;

	@Value("${cmndatasvc.cmnDataSvcEndPoinURL}")
	protected String cmnDataSvcEndPoinURL;

	@Value("${cmndatasvc.cmnDataSvcUser}")
	protected String cmnDataSvcUser;

	@Value("${cmndatasvc.cmnDataSvcPwd}")
	protected String cmnDataSvcPwd;

	@Value("${http_proxy}")
	protected String http_proxy;

	@Value("${requirements.extraIndexURL}")
	protected String extraIndexURL;

	@Value("${requirements.trustedHost}")
	protected String trustedHost;

	@Value("${mktPlace.mktPlaceEndPointURL}")
	protected String portalURL;
	
	protected String modelOriginalName = null;

	@Autowired
	ResourceLoader resourceLoader;

	@Autowired
	protected DockerConfiguration dockerConfiguration;
	
	protected MetadataParser metadataParser;
	
	protected CommonDataServiceRestClientImpl cdmsClient;

	protected PortalRestClientImpl portalClient;

	ResourceUtils resourceUtils;
	
	@Autowired
	CommonOnboarding commonOnboarding;
	
	public static final String logPath = "/maven/logs/microservice-generation/applog";
	
	Map<String, String> artifactsDetails = new HashMap<>();
	
	static String imgUri = null;
	
	@PostConstruct
	public void init() {
		logger.debug(EELFLoggerDelegate.debugLogger,"Creating docker service instance");
		this.cdmsClient = new CommonDataServiceRestClientImpl(cmnDataSvcEndPoinURL, cmnDataSvcUser, cmnDataSvcPwd, null);
		this.portalClient = new PortalRestClientImpl(portalURL);
		this.resourceUtils = new ResourceUtils(resourceLoader);
	}
	
	/*
	 * @Method Name : dockerizeFile Performs complete dockerization process.
	 */
	public String dockerizeFile(MetadataParser metadataParser, File localmodelFile, String solutionID, String deployment_env, File tempFolder) throws AcumosServiceException {
		File outputFolder = tempFolder;
		Metadata metadata = metadataParser.getMetadata();
		logger.debug(EELFLoggerDelegate.debugLogger,"Preparing app in: {}", tempFolder);
		if (metadata.getRuntimeName().equals("python")) {
			outputFolder = new File(tempFolder, "app");
			outputFolder.mkdir();
			
			Resource[] resources = null;
			
			if(deployment_env.equalsIgnoreCase("2"))
			{
				resources = resourceUtils.loadResources("classpath*:templates/dcae_python/*");
			}
			else
			{
				resources = this.resourceUtils.loadResources("classpath*:templates/python/*");
			}

			PythonDockerPreprator dockerPreprator = new PythonDockerPreprator(metadataParser, extraIndexURL,
					trustedHost,http_proxy);
			
			for (Resource resource : resources) {
				UtilityFunction.copyFile(resource, new File(outputFolder, resource.getFilename()));
			}
			try {
				File modelFolder = new File(outputFolder, "model");
				UtilityFunction.unzip(localmodelFile, modelFolder.getAbsolutePath());
			} catch (IOException e) {
				logger.error(EELFLoggerDelegate.errorLogger,"Python templatization failed: {}", e);
			}
			dockerPreprator.prepareDockerAppV2(outputFolder);
		} else if (metadata.getRuntimeName().equals("r")) {			
			RDockerPreparator dockerPreprator = new RDockerPreparator(metadataParser, http_proxy);
			Resource[] resources = this.resourceUtils.loadResources("classpath*:templates/r/*");
			for (Resource resource : resources) {
				UtilityFunction.copyFile(resource, new File(outputFolder, resource.getFilename()));
			}
			dockerPreprator.prepareDockerApp(outputFolder);
		} else if (metadata.getRuntimeName().equals("javaargus")) {
			try {
				String outputFile = UtilityFunction.getFileName(localmodelFile, outputFolder.toString());
				File tarFile = new File(outputFile);
				tarFile = UtilityFunction.deCompressGZipFile(localmodelFile, tarFile);
				UtilityFunction.unTarFile(tarFile, outputFolder);
			} catch (IOException e) {
				logger.error(EELFLoggerDelegate.errorLogger,"Java Argus templatization failed: {}", e);
			}
		} else if (metadata.getRuntimeName().equals("h2o")) {
			File plugin_root = new File(outputFolder, "plugin_root");
			plugin_root.mkdirs(); 
			File plugin_src = new File(plugin_root, "src");
			plugin_src.mkdirs();
			File plugin_classes = new File(plugin_root, "classes");
			plugin_classes.mkdirs();

			H2ODockerPreparator dockerPreprator = new H2ODockerPreparator(metadataParser);

			Resource[] resources = this.resourceUtils.loadResources("classpath*:templates/h2o/*");
			for (Resource resource : resources) {
				UtilityFunction.copyFile(resource, new File(outputFolder, resource.getFilename()));
			}
			try {
				UtilityFunction.unzip(localmodelFile, outputFolder.getAbsolutePath());

				String mm[] = modelOriginalName.split("\\.");

				File fd = new File(outputFolder.getAbsolutePath() + "/" + mm[0]);

				File ff[] = fd.listFiles();

				if (ff != null) {
					for (File f : ff) {
						FileUtils.copyFileToDirectory(f, outputFolder);
					}
					UtilityFunction.deleteDirectory(new File(outputFolder.getAbsolutePath() + "/" + modelOriginalName));
					UtilityFunction.deleteDirectory(new File(outputFolder.getAbsolutePath() + "/" + mm[0]));
				}

				// Creat solution id - success
			} catch (IOException e) {
				logger.error(EELFLoggerDelegate.errorLogger,"H2O templatization failed", e);
			}
			dockerPreprator.prepareDockerApp(outputFolder);

		} else if (metadata.getRuntimeName().equals("javageneric")) {
			File plugin_root = new File(outputFolder, "plugin_root");
			plugin_root.mkdirs();
			File plugin_src = new File(plugin_root, "src");
			plugin_src.mkdirs();
			File plugin_classes = new File(plugin_root, "classes");
			plugin_classes.mkdirs();

			JavaGenericDockerPreparator dockerPreprator = new JavaGenericDockerPreparator(metadataParser);
			Resource[] resources = this.resourceUtils.loadResources("classpath*:templates/javaGeneric/*");
			for (Resource resource : resources) {
				UtilityFunction.copyFile(resource, new File(outputFolder, resource.getFilename()));
			}

			try {

				UtilityFunction.unzip(localmodelFile, outputFolder.getAbsolutePath());

				String mm[] = modelOriginalName.split("\\.");

				File fd = new File(outputFolder.getAbsolutePath() + "/" + mm[0]);

				File ff[] = fd.listFiles();

				if (ff != null) {
					for (File f : ff) {
						FileUtils.copyFileToDirectory(f, outputFolder);
					}
					UtilityFunction.deleteDirectory(new File(outputFolder.getAbsolutePath() + "/" + modelOriginalName));
					UtilityFunction.deleteDirectory(new File(outputFolder.getAbsolutePath() + "/" + mm[0]));
				}

			} catch (IOException e) {
				logger.error(EELFLoggerDelegate.errorLogger,"Java-Generic templatization failed", e);
			}

			dockerPreprator.prepareDockerApp(outputFolder);

		} else {
			logger.error(EELFLoggerDelegate.errorLogger,"Unspported runtime {}", metadata.getRuntimeName());
			throw new AcumosServiceException(AcumosServiceException.ErrorCode.INVALID_PARAMETER,
					"Unspported runtime " + metadata.getRuntimeName());
		}
		logger.debug(EELFLoggerDelegate.debugLogger,"Resource List");
		listFilesAndFilesSubDirectories(outputFolder);
		logger.debug(EELFLoggerDelegate.debugLogger,"End of Resource List");
		logger.debug(EELFLoggerDelegate.debugLogger,"Started docker client");
		DockerClient dockerClient = DockerClientFactory.getDockerClient(dockerConfiguration);
		logger.debug(EELFLoggerDelegate.debugLogger,"Docker client created successfully");
		try {			
			logger.debug("Docker image creation started");
			String actualModelName = getActualModelName(metadata, solutionID);  
			CreateImageCommand createCMD = new CreateImageCommand(outputFolder, actualModelName,metadata.getVersion(), null, false, true);
			createCMD.setClient(dockerClient);
			createCMD.execute();
			logger.debug(EELFLoggerDelegate.debugLogger,"Docker image creation done");
			// put catch here
			// /Microservice/Docker image nexus creation -success

			// in catch /Microservice/Docker image nexus creation -failure

			// TODO: remove local image

			logger.debug(EELFLoggerDelegate.debugLogger,"Starting docker image tagging");
			String imageTagName = dockerConfiguration.getImagetagPrefix() + File.separator + actualModelName;
			
			String dockerImageURI = imageTagName + ":" + metadata.getVersion();
			
			TagImageCommand tagImageCommand = new TagImageCommand(actualModelName+ ":" + metadata.getVersion(),
					imageTagName, metadata.getVersion(), true, false);
			tagImageCommand.setClient(dockerClient);
			tagImageCommand.execute();
			logger.debug(EELFLoggerDelegate.debugLogger,"Docker image tagging completed successfully");

			logger.debug(EELFLoggerDelegate.debugLogger,"Starting pushing with Imagename:" + imageTagName + " and version : " + metadata.getVersion()
					+ " in nexus");
			PushImageCommand pushImageCmd = new PushImageCommand(imageTagName, metadata.getVersion(), "");
			pushImageCmd.setClient(dockerClient);
			pushImageCmd.execute();

			logger.debug(EELFLoggerDelegate.debugLogger,"Docker image URI : " + dockerImageURI);

			logger.debug(EELFLoggerDelegate.debugLogger,"Docker image pushed in nexus successfully");

			// Microservice/Docker image pushed to nexus -success

			return dockerImageURI;

		} finally {
			try {
				dockerClient.close();
			} catch (IOException e) {
				logger.error(EELFLoggerDelegate.errorLogger,"Fail to close docker client gracefully", e);
			}
		}
	}
	
	public void dockerizeFileAsync(OnboardingNotification onboardingStatus, MetadataParser metadataParser,
			File localmodelFile, String solutionID, String deployment_env, File tempFolder, String trackingID, String fileName, LogThreadLocal logThread)
			throws AcumosServiceException {
		File outputFolder = tempFolder;
		Metadata metadata = metadataParser.getMetadata();
		boolean isSuccess = false;
		logger.debug(EELFLoggerDelegate.debugLogger,"Preparing app in: {}", tempFolder);
		if (metadata.getRuntimeName().equals("python")) {
			outputFolder = new File(tempFolder, "app");
			outputFolder.mkdir();
			
			Resource[] resources = null;
			
			if(deployment_env.equalsIgnoreCase("2"))
			{
				resources = resourceUtils.loadResources("classpath*:templates/dcae_python/*");
			}
			else
			{
				resources = this.resourceUtils.loadResources("classpath*:templates/python/*");
			}

			PythonDockerPreprator dockerPreprator = new PythonDockerPreprator(metadataParser, extraIndexURL,
					trustedHost,http_proxy);
			
			for (Resource resource : resources) {
				UtilityFunction.copyFile(resource, new File(outputFolder, resource.getFilename()));
			}
			try {
				File modelFolder = new File(outputFolder, "model");
				UtilityFunction.unzip(localmodelFile, modelFolder.getAbsolutePath());
			} catch (IOException e) {
				logger.error(EELFLoggerDelegate.errorLogger,"Python templatization failed: {}", e);
			}
			dockerPreprator.prepareDockerAppV2(outputFolder);
		} else if (metadata.getRuntimeName().equals("r")) {			
			RDockerPreparator dockerPreprator = new RDockerPreparator(metadataParser, http_proxy);
			Resource[] resources = this.resourceUtils.loadResources("classpath*:templates/r/*");
			for (Resource resource : resources) {
				UtilityFunction.copyFile(resource, new File(outputFolder, resource.getFilename()));
			}
			dockerPreprator.prepareDockerApp(outputFolder);
		} else if (metadata.getRuntimeName().equals("javaargus")) {
			try {
				String outputFile = UtilityFunction.getFileName(localmodelFile, outputFolder.toString());
				File tarFile = new File(outputFile);
				tarFile = UtilityFunction.deCompressGZipFile(localmodelFile, tarFile);
				UtilityFunction.unTarFile(tarFile, outputFolder);
			} catch (IOException e) {
				logger.error(EELFLoggerDelegate.errorLogger,"Java Argus templatization failed: {}", e);
			}
		} else if (metadata.getRuntimeName().equals("h2o")) {
			File plugin_root = new File(outputFolder, "plugin_root");
			plugin_root.mkdirs(); 
			File plugin_src = new File(plugin_root, "src");
			plugin_src.mkdirs();
			File plugin_classes = new File(plugin_root, "classes");
			plugin_classes.mkdirs();

			H2ODockerPreparator dockerPreprator = new H2ODockerPreparator(metadataParser);

			Resource[] resources = this.resourceUtils.loadResources("classpath*:templates/h2o/*");
			for (Resource resource : resources) {
				UtilityFunction.copyFile(resource, new File(outputFolder, resource.getFilename()));
			}
			try {
				UtilityFunction.unzip(localmodelFile, outputFolder.getAbsolutePath());

				String mm[] = modelOriginalName.split("\\.");

				File fd = new File(outputFolder.getAbsolutePath() + "/" + mm[0]);

				File ff[] = fd.listFiles();

				if (ff != null) {
					for (File f : ff) {
						FileUtils.copyFileToDirectory(f, outputFolder);
					}
					UtilityFunction.deleteDirectory(new File(outputFolder.getAbsolutePath() + "/" + modelOriginalName));
					UtilityFunction.deleteDirectory(new File(outputFolder.getAbsolutePath() + "/" + mm[0]));
				}

				// Creat solution id - success
			} catch (IOException e) {
				logger.error(EELFLoggerDelegate.errorLogger,"H2O templatization failed", e);
			}
			dockerPreprator.prepareDockerApp(outputFolder);

		} else if (metadata.getRuntimeName().equals("javageneric")) {
			File plugin_root = new File(outputFolder, "plugin_root");
			plugin_root.mkdirs();
			File plugin_src = new File(plugin_root, "src");
			plugin_src.mkdirs();
			File plugin_classes = new File(plugin_root, "classes");
			plugin_classes.mkdirs();

			JavaGenericDockerPreparator dockerPreprator = new JavaGenericDockerPreparator(metadataParser);
			Resource[] resources = this.resourceUtils.loadResources("classpath*:templates/javaGeneric/*");
			for (Resource resource : resources) {
				UtilityFunction.copyFile(resource, new File(outputFolder, resource.getFilename()));
			}

			try {

				UtilityFunction.unzip(localmodelFile, outputFolder.getAbsolutePath());

				String mm[] = modelOriginalName.split("\\.");

				File fd = new File(outputFolder.getAbsolutePath() + "/" + mm[0]);

				File ff[] = fd.listFiles();

				if (ff != null) {
					for (File f : ff) {
						FileUtils.copyFileToDirectory(f, outputFolder);
					}
					UtilityFunction.deleteDirectory(new File(outputFolder.getAbsolutePath() + "/" + modelOriginalName));
					UtilityFunction.deleteDirectory(new File(outputFolder.getAbsolutePath() + "/" + mm[0]));
				}

			} catch (IOException e) {
				logger.error(EELFLoggerDelegate.errorLogger,"Java-Generic templatization failed", e);
			}

			dockerPreprator.prepareDockerApp(outputFolder);

		} else {
			logger.error(EELFLoggerDelegate.errorLogger,"Unspported runtime {}", metadata.getRuntimeName());
			throw new AcumosServiceException(AcumosServiceException.ErrorCode.INVALID_PARAMETER,
					"Unspported runtime " + metadata.getRuntimeName());
		}
		logger.debug(EELFLoggerDelegate.debugLogger,"Resource List");
		listFilesAndFilesSubDirectories(outputFolder);
		logger.debug(EELFLoggerDelegate.debugLogger,"End of Resource List");
		logger.debug(EELFLoggerDelegate.debugLogger,"Started docker client");
		DockerClient dockerClient = DockerClientFactory.getDockerClient(dockerConfiguration);
		logger.debug(EELFLoggerDelegate.debugLogger,"Docker client created successfully");
		
		Metadata mData = metadataParser.getMetadata();
		String imageUri = null;
		
		try {			
			logger.debug("Docker image creation started");
			String actualModelName = getActualModelName(metadata, solutionID);  
			CreateImageCommand createCMD = new CreateImageCommand(outputFolder, actualModelName,metadata.getVersion(), null, false, true);
			createCMD.setClient(dockerClient);
			createCMD.execute();
			logger.debug(EELFLoggerDelegate.debugLogger,"Docker image creation done");
			// put catch here
			// /Microservice/Docker image nexus creation -success

			// in catch /Microservice/Docker image nexus creation -failure

			// TODO: remove local image

			logger.debug(EELFLoggerDelegate.debugLogger,"Starting docker image tagging");
			String imageTagName = dockerConfiguration.getImagetagPrefix() + File.separator + actualModelName;
			
			String dockerImageURI = imageTagName + ":" + metadata.getVersion();
			
			TagImageCommand tagImageCommand = new TagImageCommand(actualModelName+ ":" + metadata.getVersion(),
					imageTagName, metadata.getVersion(), true, false);
			tagImageCommand.setClient(dockerClient);
			tagImageCommand.execute();
			logger.debug(EELFLoggerDelegate.debugLogger,"Docker image tagging completed successfully");

			logger.debug(EELFLoggerDelegate.debugLogger,"Starting pushing with Imagename:" + imageTagName + " and version : " + metadata.getVersion()
					+ " in nexus");
			PushImageCommand pushImageCmd = new PushImageCommand(imageTagName, metadata.getVersion(), "");
			pushImageCmd.setClient(dockerClient);
			pushImageCmd.execute();

			logger.debug(EELFLoggerDelegate.debugLogger,"Docker image URI : " + dockerImageURI);

			// Microservice/Docker image pushed to nexus -success
			logger.debug(EELFLoggerDelegate.debugLogger,"Docker image pushed in nexus successfully");

			
			
			// Notify Create docker image is successful
			if (onboardingStatus != null) {
				onboardingStatus.notifyOnboardingStatus("Dockerize", "SU",
						"Created Docker Image Successfully for solution " + mData.getSolutionId());
			}
			
			// Add artifacts started. Notification will be handed by
			// addArtifact method itself for started/success/failure
			artifactsDetails = getArtifactsDetails();
			commonOnboarding.addArtifact(mData, dockerImageURI, getArtifactTypeCode("Docker Image"),
					onboardingStatus);
			
			if (deployment_env.equalsIgnoreCase("2")) {
				logger.debug(EELFLoggerDelegate.debugLogger, "OutputFolderPath: " + outputFolder);
				logger.debug(EELFLoggerDelegate.debugLogger,
						"AbsolutePath OutputFolderPath: " + outputFolder.getAbsolutePath());
				addDCAEArrtifacts(mData, outputFolder, solutionID, onboardingStatus);
			}
			
			isSuccess = true;
		
		} finally {
			
			try {
				
				logger.debug(EELFLoggerDelegate.debugLogger,"Thread in finally block of dockerizeFileAsync --> "+Thread.currentThread().getName());
				UtilityFunction.deleteDirectory(outputFolder);

				if (isSuccess == false) {
					logger.debug(EELFLoggerDelegate.debugLogger,
							"Onboarding Failed, Reverting failed solutions and artifacts.");
					if (metadataParser != null && mData != null) {
						revertbackOnboarding(mData, solutionID, imageUri);
					}
				}

				// push docker build log into nexus
				File file = new java.io.File(logPath + File.separator + trackingID + File.separator + fileName);
				logger.debug(EELFLoggerDelegate.debugLogger, "Log file length " + file.length());
				logger.debug(EELFLoggerDelegate.debugLogger, "Log file Path " + file.getPath() + " Absolute Path : "
						+ file.getAbsolutePath() + " Canonical Path: " + file.getCanonicalFile());

				if (metadataParser != null && mData != null) {
					logger.debug(EELFLoggerDelegate.debugLogger,
							"Adding of log artifacts into nexus started " + fileName);

					String nexusArtifactID = "MicroserviceGenerationLog";

					commonOnboarding.addArtifact(mData, file, "LG", nexusArtifactID, onboardingStatus);
					MDC.put(OnboardingLogConstants.MDCs.RESPONSE_STATUS_CODE,
							OnboardingLogConstants.ResponseStatus.COMPLETED.name());
					logger.debug(EELFLoggerDelegate.debugLogger,
							"Artifacts log pushed to nexus successfully" + fileName);
				}
				// delete log file
				UtilityFunction.deleteDirectory(file);
				logThread.unset();
				mData = null;

				dockerClient.close();
			} catch (AcumosServiceException e) {
				mData = null;
				MDC.put(OnboardingLogConstants.MDCs.RESPONSE_STATUS_CODE,
						OnboardingLogConstants.ResponseStatus.ERROR.name());
				MDC.put(OnboardingLogConstants.MDCs.RESPONSE_DESCRIPTION, e.getMessage());
				logger.error(EELFLoggerDelegate.errorLogger, "RevertbackOnboarding Failed");
				HttpStatus httpCode = HttpStatus.INTERNAL_SERVER_ERROR;
				
			} catch (IOException e) {
				logger.error(EELFLoggerDelegate.errorLogger,"Fail to close docker client gracefully", e);
			}
		}
	}
	
	public void listFilesAndFilesSubDirectories(File directory) {

		File[] fList = directory.listFiles();

		for (File file : fList) {
			if (file.isFile()) {
				logger.debug(EELFLoggerDelegate.debugLogger,file.getName());
			} else if (file.isDirectory()) {
				listFilesAndFilesSubDirectories(file);
			}
		}
	}
	
	public String getActualModelName(Metadata metadata, String solutionID) {

		return metadata.getModelName() + "_" + solutionID;
	}
	
	public void revertbackOnboarding(Metadata metadata,String solutionID, String imageUri) throws AcumosServiceException {

		try {

			logger.debug(EELFLoggerDelegate.debugLogger,"In RevertbackOnboarding method");
			RepositoryLocation repositoryLocation = new RepositoryLocation();
			repositoryLocation.setId("1");
			repositoryLocation.setUrl(nexusEndPointURL);
			repositoryLocation.setUsername(nexusUserName);
			repositoryLocation.setPassword(nexusPassword);
			NexusArtifactClient nexusClient = new NexusArtifactClient(repositoryLocation);
			DockerClient dockerClient = DockerClientFactory.getDockerClient(dockerConfiguration);

			// Remove the image from docker registry
			// Check the value of imageUri, if it is null then do not delete the
			// image
			logger.debug(EELFLoggerDelegate.debugLogger,"Image Name from dockerize file method: " + imageUri);

			if (StringUtils.isNotBlank(imageUri)) {
				String imageTagName = dockerConfiguration.getImagetagPrefix() + "/" + getActualModelName(metadata, solutionID);
				
				logger.debug(EELFLoggerDelegate.debugLogger,"Image Name: " + imageTagName);
				DeleteImageCommand deleteImageCommand = new DeleteImageCommand(imageTagName, metadata.getVersion(), "");
				deleteImageCommand.setClient(dockerClient);
				deleteImageCommand.execute();
				logger.debug(EELFLoggerDelegate.debugLogger,"Successfully Deleted the image from Docker Registry");
			}

			if (metadata.getSolutionId() != null) {
				logger.debug(EELFLoggerDelegate.debugLogger,"Solution id: " + metadata.getSolutionId() + "  Revision id: " + metadata.getRevisionId());

				// get the Artifact IDs for given solution
				List<MLPArtifact> artifactids = cdmsClient.getSolutionRevisionArtifacts(metadata.getSolutionId(),
						metadata.getRevisionId());

				// check if artifactids is empty
				// Delete all the artifacts for given solution

				/*for (MLPArtifact mlpArtifact : artifactids) {
					String artifactId = mlpArtifact.getArtifactId();

					// Delete SolutionRevisionArtifact
					logger.debug(EELFLoggerDelegate.debugLogger,"Deleting Artifact: " + artifactId);
					cdmsClient.dropSolutionRevisionArtifact(metadata.getSolutionId(), metadata.getRevisionId(),
							artifactId);
					logger.debug(EELFLoggerDelegate.debugLogger,"Successfully Deleted the SolutionRevisionArtifact");

					// Delete Artifact
					cdmsClient.deleteArtifact(artifactId);
					logger.debug(EELFLoggerDelegate.debugLogger,"Successfully Deleted the Artifact");

					// Delete the file from the Nexus
					if (!(mlpArtifact.getArtifactTypeCode().equals("DI"))) {
						nexusClient.deleteArtifact(mlpArtifact.getUri());
						logger.debug(EELFLoggerDelegate.debugLogger,"Successfully Deleted the Artifact from Nexus");
					}
				}*/

			}
		} catch (Exception e) {
			logger.error(EELFLoggerDelegate.errorLogger,"Onboarding failed");
			logger.error(EELFLoggerDelegate.errorLogger,e.getMessage(), e);
			throw new AcumosServiceException(AcumosServiceException.ErrorCode.INTERNAL_SERVER_ERROR,
					"Fail to revert back onboarding changes : " + e.getMessage());
		}
	}
	
	protected ResponseEntity<ServiceResponse> generateMicroserviceDef(OnboardingNotification onboardingStatus, String solutioId,
			String revisionId, String modName, String deployment_env, String authorization, String trackingID,
			String provider) {

		String artifactName = null;
		File files = null;
		List<String> artifactNameList = new ArrayList<String>();
		Metadata mData = null;
		MLPSolution mlpSolution = new MLPSolution();
		MLPSolutionRevision revision;

		try {

			// Call to validate Token.....!
			String ownerId = commonOnboarding.validate(authorization, provider);
			if (ownerId != null && !ownerId.isEmpty()) {

				logger.debug(EELFLoggerDelegate.debugLogger, "Token validation successful");

			
			} else {

				logger.error(EELFLoggerDelegate.errorLogger, "Either Username/Password is invalid.");
				MDC.put(OnboardingLogConstants.MDCs.RESPONSE_STATUS_CODE,
						OnboardingLogConstants.ResponseStatus.ERROR.name());
				MDC.put(OnboardingLogConstants.MDCs.RESPONSE_DESCRIPTION, "Either Username/Password is invalid.");
				// throw new
				// AcumosServiceException(AcumosServiceException.ErrorCode.INVALID_TOKEN,
				// "Either Username/Password is invalid.");
				return new ResponseEntity<ServiceResponse>(
						ServiceResponse.errorResponse("" + HttpStatus.UNAUTHORIZED, "Unauthorized User"),
						HttpStatus.UNAUTHORIZED);
			}

			String fileName = "MicroserviceGenerationLog.txt";
			// setting log filename in ThreadLocal
			LogBean logBean = new LogBean();
			logBean.setFileName(fileName);
			logBean.setLogPath(logPath + File.separator + trackingID);

			LogThreadLocal logThread = new LogThreadLocal();
			logThread.set(logBean);
			// create log file to capture logs as artifact
			createLogFile(logBean.getLogPath());

			String buildVersion = UtilityFunction.getProjectVersion();
			logger.debug(EELFLoggerDelegate.debugLogger, "Microservice-Generation version : " + buildVersion);

			String modelName = null;

			logger.debug(EELFLoggerDelegate.debugLogger, "Fetching model from Nexus...!");

			// Nexus Integration....!

			DownloadModelArtifacts download = new DownloadModelArtifacts();
			logger.debug(EELFLoggerDelegate.debugLogger, "solutioId: {}", solutioId, "revisionId: {}", revisionId);
			artifactNameList = download.getModelArtifacts(solutioId, revisionId, cmnDataSvcUser, cmnDataSvcPwd,
					nexusEndPointURL, nexusUserName, nexusPassword, cmnDataSvcEndPoinURL);

			logger.debug(EELFLoggerDelegate.debugLogger, "Number of artifacts: ", artifactNameList.size());

			logger.debug(EELFLoggerDelegate.debugLogger, "Starting Microservice Generation");

			String modelId = UtilityFunction.getGUID();
			File outputFolder = new File("tmp", modelId);
			outputFolder.mkdirs();

			files = new File("model");

			MultipartFile model = null, meta = null, proto = null;

			File modelFile = null, MetaFile = null, protoFile = null;

			for (String name : artifactNameList) {
				if (name.contains(".json")) {
					logger.debug(EELFLoggerDelegate.debugLogger, "MetaFile: {}", name);
					MetaFile = new File(files, name);
					UtilityFunction.copyFile(MetaFile, new File(outputFolder, name));
				} else if (name.contains(".proto")) {
					logger.debug(EELFLoggerDelegate.debugLogger, "ProtoFile: {}", name);
					protoFile = new File(files, name);
					UtilityFunction.copyFile(protoFile, new File(outputFolder, name));
				} else {
					logger.debug(EELFLoggerDelegate.debugLogger, "ModelFile: {}", name);
					modelFile = new File(files, name);
					UtilityFunction.copyFile(modelFile, new File(outputFolder, name));
				}
			}

			if (modName != null) {
				Object obj = new JSONParser().parse(new FileReader(MetaFile));
				JSONObject jo = (JSONObject) obj;
				jo.put("name", modName);
				String jsonFile = jo.toString();
				FileOutputStream fout = new FileOutputStream(MetaFile);
				fout.write(jsonFile.getBytes());
				fout.close();
			}

			if ((modelFile.exists()) && (MetaFile.exists()) && (protoFile.exists())) {
				metadataParser = new MetadataParser(MetaFile);
				mData = metadataParser.getMetadata();
				mData.setOwnerId(ownerId);

				FileInputStream fisModel = new FileInputStream(modelFile);
				model = new MockMultipartFile("Model", modelFile.getName(), "", fisModel);

				FileInputStream fisMeta = new FileInputStream(MetaFile);
				meta = new MockMultipartFile("Metadata", MetaFile.getName(), "", fisMeta);

				FileInputStream fisProto = new FileInputStream(protoFile);
				proto = new MockMultipartFile("Proto", protoFile.getName(), "", fisProto);

				if (deployment_env.equalsIgnoreCase("2")) {

					List<MLPSolution> solList = commonOnboarding.getExistingSolution(mData);

					boolean isListEmpty = solList.isEmpty();

					if (isListEmpty) {

						mlpSolution = commonOnboarding.createSolution(mData, onboardingStatus);
						mData.setSolutionId(mlpSolution.getSolutionId());
						logger.debug(EELFLoggerDelegate.debugLogger,
								"New solution created Successfully for ONAP" + mlpSolution.getSolutionId());
					} else {
						logger.debug(EELFLoggerDelegate.debugLogger,
								"Existing solution found for ONAP model name " + solList.get(0).getName());
						mlpSolution = solList.get(0);
						mData.setSolutionId(mlpSolution.getSolutionId());
						mlpSolution.setName(mData.getSolutionName());
						// mlpSolution.setDescription(mData.getSolutionName());
						mlpSolution.setUserId(mData.getOwnerId());
					}

					revision = commonOnboarding.createSolutionRevision(mData);
					logger.debug(EELFLoggerDelegate.debugLogger,
							"Revision created Successfully  for ONAP" + revision.getRevisionId());
					mData.setRevisionId(revision.getRevisionId());

					modelName = mData.getModelName() + "_" + mData.getSolutionId();
				} else if (solutioId != null && revisionId != null) {
					mData.setSolutionId(solutioId);
					mData.setRevisionId(revisionId);
					mlpSolution.setSolutionId(solutioId);
					mlpSolution.setName(mData.getSolutionName());
					// mlpSolution.setDescription(mData.getSolutionName());
					mlpSolution.setUserId(mData.getOwnerId());
				} else {
					logger.error(EELFLoggerDelegate.errorLogger, "Invalid Request................");
					throw new AcumosServiceException(AcumosServiceException.ErrorCode.INVALID_PARAMETER,
							"Invalid Request...............");
				}

				String version = mData.getVersion();

				if (version == null) {
					version = getModelVersion(mData.getSolutionId(), mData.getRevisionId());
					mData.setVersion(version);
				}

				MLPUser shareUser = null;

				/*
				 * // try { // 'authorization' represents JWT token here...! if (authorization
				 * == null) { logger.error(EELFLoggerDelegate.errorLogger,
				 * "Token Not Available...!"); throw new
				 * AcumosServiceException(AcumosServiceException.ErrorCode.OBJECT_NOT_FOUND,
				 * "Token Not Available...!"); }
				 */

				String imageUri = null;

				if (ownerId != null && !ownerId.isEmpty()) {

					logger.debug(EELFLoggerDelegate.debugLogger,
							"Dockerization request recieved with " + model.getOriginalFilename());

					modelOriginalName = model.getOriginalFilename();
					//boolean isSuccess = false;

						// Solution id creation completed
						// Notify Creation of solution ID is successful
						if (onboardingStatus != null) {
							// set solution Id
							if (mlpSolution.getSolutionId() != null) {
								onboardingStatus.setSolutionId(mlpSolution.getSolutionId());
							}
							// set revision id
							if (mData.getRevisionId() != null) {
								onboardingStatus.setRevisionId(mData.getRevisionId());
							}
						}

						// Notify Create docker image has started
						if (onboardingStatus != null) {
							
							logger.debug(EELFLoggerDelegate.debugLogger, "Setting values in Task object");

							MLPTask task = new MLPTask();
							task.setTaskCode("OB");
							task.setStatusCode("ST");
							task.setName("OnBoarding");
							task.setUserId(ownerId);
							task.setCreated(Instant.now());
							task.setModified(Instant.now());
							task.setTrackingId(trackingID);
							task.setSolutionId(mlpSolution.getSolutionId());
							task.setRevisionId(mData.getRevisionId());
							
							onboardingStatus.setTrackingId(trackingID);
							onboardingStatus.setUserId(ownerId);
							
							logger.debug(EELFLoggerDelegate.debugLogger, "Task Details: " + task.toString());
							
							task = cdmsClient.createTask(task);

							logger.debug(EELFLoggerDelegate.debugLogger, "TaskID: " + task.getTaskId());

							onboardingStatus.setTaskId(task.getTaskId());

							onboardingStatus.notifyOnboardingStatus("Dockerize", "ST",
									"Create Docker Image Started for solution " + mData.getSolutionId());
						}

						try {
							File modFile = modelFile;
							MLPSolution mlpSoln = mlpSolution;
							logger.debug(EELFLoggerDelegate.debugLogger,"Thread before calling dockerizeFileAsync --> "+Thread.currentThread().getName());
							CompletableFuture.supplyAsync(() -> {
								try {
									 dockerizeFileAsync(onboardingStatus, metadataParser, modFile, mlpSoln.getSolutionId(),
											deployment_env, outputFolder, trackingID, fileName, logThread);
								} catch (AcumosServiceException e) {
									logger.error(EELFLoggerDelegate.errorLogger,
											"Exception while creating docker image : " + e);
								}
								return null;
							});
							
						} catch (Exception e) {
							// Notify Create docker image failed
							if (onboardingStatus != null) {
								onboardingStatus.notifyOnboardingStatus("Dockerize", "FA", e.getMessage());
							}

							MDC.put(OnboardingLogConstants.MDCs.RESPONSE_STATUS_CODE,
									OnboardingLogConstants.ResponseStatus.ERROR.name());
							MDC.put(OnboardingLogConstants.MDCs.RESPONSE_DESCRIPTION, e.getMessage());
							logger.error(EELFLoggerDelegate.errorLogger, "Error {}", e);
							throw e;
						}

						return new ResponseEntity<ServiceResponse>(ServiceResponse.successResponse(mlpSolution),
								HttpStatus.CREATED);

							// delete the Docker image
							/*
							 * logger.debug(EELFLoggerDelegate.
							 * debugLogger,"Docker image Deletion started -> image = "+imageUri+", tag = "
							 * +mData.getVersion()); DeleteImageCommand deleteCMD = new
							 * DeleteImageCommand(imageUri, mData.getVersion(), null); deleteCMD.execute();
							 * logger.debug(EELFLoggerDelegate.debugLogger,"Docker image Deletion Done");
							 */

				} else {
					try {
						MDC.put(OnboardingLogConstants.MDCs.RESPONSE_STATUS_CODE,
								OnboardingLogConstants.ResponseStatus.ERROR.name());
						MDC.put(OnboardingLogConstants.MDCs.RESPONSE_DESCRIPTION,
								"Either Username/Password is invalid");
						logger.error(EELFLoggerDelegate.errorLogger, "Either Username/Password is invalid.");
						throw new AcumosServiceException(AcumosServiceException.ErrorCode.INVALID_TOKEN,
								"Either Username/Password is invalid.");
					} catch (AcumosServiceException e) {
						return new ResponseEntity<ServiceResponse>(
								ServiceResponse.errorResponse(e.getErrorCode(), e.getMessage()),
								HttpStatus.UNAUTHORIZED);
					}
				}
			} else {
				logger.error(EELFLoggerDelegate.errorLogger, "Model artifacts not available..!");
				throw new AcumosServiceException("Model artifacts not available..!");
			}

		} catch (AcumosServiceException e) {

			HttpStatus httpCode = HttpStatus.INTERNAL_SERVER_ERROR;
			MDC.put(OnboardingLogConstants.MDCs.RESPONSE_STATUS_CODE,OnboardingLogConstants.ResponseStatus.ERROR.name());
			MDC.put(OnboardingLogConstants.MDCs.RESPONSE_DESCRIPTION, e.getMessage());
			logger.error(EELFLoggerDelegate.errorLogger, e.getErrorCode() + "  " + e.getMessage());
			if (e.getErrorCode().equalsIgnoreCase(OnboardingConstants.INVALID_PARAMETER)) {
				httpCode = HttpStatus.BAD_REQUEST;
			}
			return new ResponseEntity<ServiceResponse>(ServiceResponse.errorResponse(e.getErrorCode(), e.getMessage()),
					httpCode);
		} catch (HttpClientErrorException e) {
			MDC.put(OnboardingLogConstants.MDCs.RESPONSE_STATUS_CODE,OnboardingLogConstants.ResponseStatus.ERROR.name());
			MDC.put(OnboardingLogConstants.MDCs.RESPONSE_DESCRIPTION, e.getMessage());
			// Handling #401
			if (HttpStatus.UNAUTHORIZED == e.getStatusCode() || HttpStatus.BAD_REQUEST == e.getStatusCode()) {
				logger.debug(EELFLoggerDelegate.debugLogger,
						"Unauthorized User - Either Username/Password is invalid.");
				return new ResponseEntity<ServiceResponse>(
						ServiceResponse.errorResponse("" + HttpStatus.UNAUTHORIZED, "Unauthorized User"),
						HttpStatus.UNAUTHORIZED);
			} else {
				logger.error(EELFLoggerDelegate.errorLogger, e.getMessage());
				e.printStackTrace();
				return new ResponseEntity<ServiceResponse>(
						ServiceResponse.errorResponse("" + e.getStatusCode(), e.getMessage()), e.getStatusCode());
			}
		} catch (Exception e) {
			MDC.put(OnboardingLogConstants.MDCs.RESPONSE_STATUS_CODE,OnboardingLogConstants.ResponseStatus.ERROR.name());
			MDC.put(OnboardingLogConstants.MDCs.RESPONSE_DESCRIPTION, e.getMessage());
			logger.error(EELFLoggerDelegate.errorLogger, e.getMessage());
			e.printStackTrace();
			if (e instanceof AcumosServiceException) {
				return new ResponseEntity<ServiceResponse>(
						ServiceResponse.errorResponse(((AcumosServiceException) e).getErrorCode(), e.getMessage()),
						HttpStatus.INTERNAL_SERVER_ERROR);
			} else {
				return new ResponseEntity<ServiceResponse>(
						ServiceResponse.errorResponse(AcumosServiceException.ErrorCode.UNKNOWN.name(), e.getMessage()),
						HttpStatus.INTERNAL_SERVER_ERROR);
			}
		}
	}
	
	private Map<String, String> getArtifactsDetails() {
		List<MLPCodeNamePair> typeCodeList = cdmsClient.getCodeNamePairs(CodeNameType.ARTIFACT_TYPE);
		Map<String, String> artifactsDetails = new HashMap<>();
		if (!typeCodeList.isEmpty()) {
			for (MLPCodeNamePair codeNamePair : typeCodeList) {
				artifactsDetails.put(codeNamePair.getName(), codeNamePair.getCode());
			}
		}
		return artifactsDetails;
	}

	private String getArtifactTypeCode(String artifactTypeName) {
		String typeCode = artifactsDetails.get(artifactTypeName);
		return typeCode;
	}

	private void addDCAEArrtifacts(Metadata mData, File outputFolder, String solutionID,
			OnboardingNotification onboardingStatus) throws AcumosServiceException {

		File filePathoutputF = new File(outputFolder, "app");

		File anoIn = new File(filePathoutputF, "anomaly-in.json");
		File anoOut = new File(filePathoutputF, "anomaly-out.json");
		File compo = new File(filePathoutputF, "component.json");
		File ons = new File(filePathoutputF, "onsdemo1.yaml");

		try {
			
			//call microservice
			logger.debug(EELFLoggerDelegate.debugLogger,"DCAE ADD Artifact Started ");
			commonOnboarding.addArtifact(mData, anoIn, getArtifactTypeCode("Metadata"), "anomaly-in", onboardingStatus);
			commonOnboarding.addArtifact(mData, anoOut, getArtifactTypeCode("Metadata"), "anomaly-out", onboardingStatus);
			commonOnboarding.addArtifact(mData, compo, getArtifactTypeCode("Metadata"), "component", onboardingStatus);
			commonOnboarding.addArtifact(mData, ons, getArtifactTypeCode("Metadata"), "onsdemo1", onboardingStatus);
			logger.debug(EELFLoggerDelegate.debugLogger,"DCAE ADD Artifact End ");
		}

		catch (AcumosServiceException e) {
			logger.error(EELFLoggerDelegate.errorLogger,"Exception occured while adding DCAE Artifacts " +e);
			throw e;
		} catch(Exception e){
			logger.error(EELFLoggerDelegate.errorLogger,"Exception occured while adding DCAE Artifacts " +e);
			throw e;
		}
	}
	
	public String getModelVersion(String solutionId, String revisionId) {
		MLPSolutionRevision revData;
		revData = cdmsClient.getSolutionRevision(solutionId, revisionId);

		return revData.getVersion();
	}
	
	public static void createLogFile(String logPath) {
		LogBean logBean = LogThreadLocal.get();
		String fileName = logBean.getFileName();

		File file = new java.io.File(logPath);
		file.mkdirs();
		try {
			File f1 = new File(file.getPath() + File.separator + fileName);
			if (!f1.exists()) {
				f1.createNewFile();
			}
			logger.debug(EELFLoggerDelegate.debugLogger,
					"Log file created successfully " + f1.getAbsolutePath());
		} catch (Exception e) {
			//info to avoid infinite loop.logger.debug call again calls addlog method
			logger.info("Failed while creating log file " + e.getMessage());
		}

	}
	
	public String getModelOriginalName() {
		return modelOriginalName;
	}

	public void setModelOriginalName(String modelOriginalName) {
		this.modelOriginalName = modelOriginalName;
	}
}
