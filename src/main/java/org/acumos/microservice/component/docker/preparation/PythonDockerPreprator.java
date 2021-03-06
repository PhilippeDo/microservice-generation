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

package org.acumos.microservice.component.docker.preparation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.acumos.onboarding.common.exception.AcumosServiceException;
import org.acumos.onboarding.common.utils.LoggerDelegate;
import org.acumos.onboarding.common.utils.UtilityFunction;
import org.acumos.onboarding.component.docker.preparation.Metadata;
import org.acumos.onboarding.component.docker.preparation.MetadataParser;
import org.acumos.onboarding.component.docker.preparation.Requirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

public class PythonDockerPreprator {
	
	private static Logger log = LoggerFactory.getLogger(PythonDockerPreprator.class);
	static LoggerDelegate logger = new LoggerDelegate(log);

	private Metadata metadata;

	private JsonNode metadataJson;

	private String pythonVersion;
	private String pythonhttpProxy;
	
	String extraIndexURL;
	String trustedHost;

	public PythonDockerPreprator(MetadataParser metadataParser, String extraIndexURL, String trustedHost, String httpProxy)
			throws AcumosServiceException {
		this.pythonhttpProxy = httpProxy;
		this.extraIndexURL = extraIndexURL;
		this.trustedHost = trustedHost;

		this.metadata = metadataParser.getMetadata();
		this.metadataJson = metadataParser.getMetadataJson();
		this.pythonVersion=metadata.getRuntimeVersion();
		
	}

	public void prepareDockerAppV2(File outputFolder) throws AcumosServiceException {
		// this.prepareYaml(new File(outputFolder, "swagger.yaml"), new
		// File(outputFolder, "swagger.yaml"));

		this.createDockerFile(new File(outputFolder, "Dockerfile"), new File(outputFolder, "Dockerfile"));
		this.createRequirementTxt(new File(outputFolder, "requirements.txt"),
				new File(outputFolder, "requirements.txt"));
	}

	public void createDockerFile(File inDockerFile, File outDockerFile) throws AcumosServiceException {
		try {
			String dockerFileAsString = new String(UtilityFunction.toBytes(inDockerFile));
			String newVersion = checkVersion(this.pythonVersion);
			String pythonVer = newVersion+"-slim"; 
			
			dockerFileAsString = MessageFormat.format(dockerFileAsString,
					new Object[] { pythonVer, extraIndexURL, trustedHost,this.pythonhttpProxy});
			FileWriter writer = new FileWriter(outDockerFile);
			try {
				writer.write(dockerFileAsString.trim());
			} finally {
				writer.close();
			}

		} catch (IOException e) {
			throw new AcumosServiceException(AcumosServiceException.ErrorCode.INTERNAL_SERVER_ERROR,
					"Fail to create dockerFile for input model", e);
		}

	}

	public void createRequirementTxt(File inRequirementFile, File outRequirementFile) throws AcumosServiceException {
		try {
			List<Requirement> requirements1 = getPipRequirements(inRequirementFile);
			Collection<Requirement> finalRequirements = mergePipRequirements(requirements1,
					this.metadata.getRequirements());
			StringBuilder reqAsString = new StringBuilder();
			for (Requirement pipRequirement : finalRequirements) {
				if (pipRequirement.version != null) {
					reqAsString.append(pipRequirement.name + pipRequirement.operator + pipRequirement.version + "\n");
				} else
					reqAsString.append(pipRequirement.name + "\n");
			}
			FileWriter writer = new FileWriter(outRequirementFile);
			try {
				writer.write(reqAsString.toString().trim());
			} finally {
				writer.close();
			}

		} catch (IOException e) {
			throw new AcumosServiceException(AcumosServiceException.ErrorCode.INTERNAL_SERVER_ERROR,
					"Fail to create requirements.txt for input model", e);
		}

	}

	public static Collection<Requirement> mergePipRequirements(List<Requirement> requirements1,
			List<Requirement> requirements2) {
		Map<String, Requirement> requirements = new LinkedHashMap<>();

		for (Requirement requirement : requirements1) {
			requirements.put(requirement.name, requirement);

		}
		for (Requirement requirement : requirements2) {
			if (requirements.containsKey(requirement.name)) {
				Requirement existing = requirements.get(requirement.name);
				if (existing.version != null) {
					if (requirement.version != null) {
						if (compareVersion(versionAsArray(existing.version), versionAsArray(requirement.version)) < 0) {

							requirements.put(requirement.name, requirement);
						}
					} else {
						requirements.put(requirement.name, requirement);
					}

				}

			} else {
				requirements.put(requirement.name, requirement);
			}

		}
		return requirements.values();
	}

	public static List<Requirement> getPipRequirements(File file) throws IOException {
		BufferedReader fileReader = new BufferedReader(new FileReader(file));
		try {
			String line = null;
			List<Requirement> requirements = new ArrayList<>();
			while ((line = fileReader.readLine()) != null) {
				Requirement req = new Requirement();
				req.name = line;
				int index = -1;
				index = line.indexOf("==");
				if (index == -1)

					index = line.indexOf(">=");

				if (index != -1) {
					req.name = line.substring(0, index);
					req.operator = line.substring(index, index + 2);
					req.version = line.substring(index + 2);
				}

				requirements.add(req);
			}
			return requirements;
		} finally {
			fileReader.close();
		}
	}

	/**
	 * returns -1 if input version is greater , 0 if equal and 1 if smaller
	 * 
	 * @param baseVersion
	 *            base version
	 * @param currentVersion
	 *            current version
	 * @return -1 if input version is greater , 0 if equal and 1 if smaller
	 */
	public static int compareVersion(int[] baseVersion, int[] currentVersion) {
		int result = 0;

		for (int i = 0; i < baseVersion.length; i++) {
			if (currentVersion.length < i + 1 || baseVersion[i] > currentVersion[i]) {
				result = 1;
				break;
			} else if (baseVersion[i] < currentVersion[i]) {
				result = -1;
				break;
			}
		}
		return result;
	}

	public static int[] versionAsArray(String version) {
		String[] versionArr = version.split("\\.");
		int[] versionIntArr = new int[versionArr.length];
		for (int i = 0; i < versionArr.length; i++) {
			versionIntArr[i] = Integer.parseInt(versionArr[i]);
		}
		return versionIntArr;
	}
	
	public static String checkVersion(String metaVersion) {

		String trimVersion = null;
		String[] versionValue = metaVersion.split("\\.");

		if (versionValue.length == 3) {
			String[] minorV = versionValue[2].split("");
			int counter = minorV.length;
			trimVersion = metaVersion.substring(0, metaVersion.length() - (counter+1));	
			logger.debug("Trimmed version: " + trimVersion);
			return trimVersion;
		} else {
			return metaVersion;
		}

	}

}
