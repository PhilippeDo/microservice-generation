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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.List;
import java.util.Properties;

import org.acumos.onboarding.common.exception.AcumosServiceException;
import org.acumos.onboarding.common.utils.LoggerDelegate;
import org.acumos.onboarding.common.utils.UtilityFunction;
import org.acumos.onboarding.component.docker.preparation.Metadata;
import org.acumos.onboarding.component.docker.preparation.MetadataParser;
import org.acumos.onboarding.component.docker.preparation.Requirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CPPDockerPreparator {
	private Metadata metadata;

	private String runtimeVersion;
	private String executable;
	private String http_proxy;

	private static final Logger log = LoggerFactory.getLogger(CPPDockerPreparator.class);
	LoggerDelegate logger = new LoggerDelegate(log);

	public CPPDockerPreparator(MetadataParser metadataParser, String http_proxy) throws AcumosServiceException {
		this.metadata = metadataParser.getMetadata();
		this.http_proxy = http_proxy;

		this.runtimeVersion = metadata.getRuntimeVersion();
		this.executable = metadata.getExecutable();

		if (executable == null || executable.isEmpty()) {
			throw new AcumosServiceException(AcumosServiceException.ErrorCode.INVALID_PARAMETER,
					"Unsupported executable value.");
		}

	}

	public void prepareDockerApp(File outputFolder) throws AcumosServiceException {

		this.createDockerFile(new File(outputFolder, "Dockerfile"), new File(outputFolder, "Dockerfile"));
		this.createRequirements(new File(outputFolder, "requirements.txt"), new File(outputFolder, "requirements.txt"));
	}

	public void createRequirements(File inPackageFile, File outPackageFile) throws AcumosServiceException {
		try {
			List<Requirement> requirements = this.metadata.getRequirements();
			StringBuilder reqBuilder = new StringBuilder();
			for (Requirement requirement : requirements) {
				reqBuilder.append("\"" + requirement.name + "\",");
			}
			String reqAsString = (reqBuilder != null && !reqBuilder.equals("")) ? reqBuilder.toString() : null;

			if (reqAsString != null && !reqAsString.isEmpty()) {
				reqAsString = reqAsString.substring(0, reqAsString.length() - 1);
			}
			String packageRFileAsString = new String(UtilityFunction.toBytes(inPackageFile));
			packageRFileAsString = MessageFormat.format(packageRFileAsString, new Object[] { reqAsString });
			FileWriter writer = new FileWriter(outPackageFile);
			try {
				writer.write(packageRFileAsString.trim());
			} finally {
				writer.close();
			}
		} catch (IOException e) {
			throw new AcumosServiceException(AcumosServiceException.ErrorCode.INTERNAL_SERVER_ERROR,
					"Fail to create dockerFile for input model", e);
		}
	}

	public void createDockerFile(File inDockerFile, File outDockerFile) throws AcumosServiceException {
		try {

			String dockerFileAsString = new String(UtilityFunction.toBytes(inDockerFile));

			dockerFileAsString = MessageFormat.format(dockerFileAsString,
					new Object[] { runtimeVersion, executable, http_proxy });

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

}
