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

package org.acumos.microservice;

import java.io.File;

import org.acumos.onboarding.common.exception.AcumosServiceException;
import org.acumos.onboarding.common.utils.LoggerDelegate;
import org.acumos.microservice.component.docker.preparation.JavaGenericDockerPreparator;
import org.acumos.onboarding.component.docker.preparation.MetadataParser;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.runners.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(MockitoJUnitRunner.class)
public class JavaGenericDockerPreparatorTest {

	String filePath = FilePathTest.filePath();
	private String H2oGenericjavaModelRunnerVersion = "2.2.3";
	
	public static Logger log = LoggerFactory.getLogger(JavaGenericDockerPreparatorTest.class);
	LoggerDelegate logger = new LoggerDelegate(log);

	File jsonFile = new File(filePath + "java_genric.json");
	File srcFile = new File(filePath + "Dockerfile");
	File outFile = new File(filePath + "Dockerfile");
	File outFolder = new File(filePath + "inFile.csv");
	File outFolder1 = new File(filePath);

	MetadataParser metadataParser = new MetadataParser(jsonFile);

	@InjectMocks
	JavaGenericDockerPreparator javaGenericDockerPreparator = new JavaGenericDockerPreparator(metadataParser,
			"http_proxy", H2oGenericjavaModelRunnerVersion);

	public JavaGenericDockerPreparatorTest() throws AcumosServiceException {
		new MetadataParser(jsonFile);
	}

	@Test
	public void compareVersionTest() {

		int[] baseVersion = { 1, 2, 3 };
		int[] currentVersion = { 4, 5, 6 };
		int result = JavaGenericDockerPreparator.compareVersion(baseVersion, currentVersion);
	}

	@Test
	public void versionAsArrayTest() {

		int[] baseVersion = JavaGenericDockerPreparator.versionAsArray("1234");
	}

	/*
	 * @Test public void createDockerFile() { try {
	 * javaGenericDockerPreparator.createDockerFile(srcFile, outFile); } catch
	 * (AcumosServiceException e) { Assert.fail("createDockerFile failed : " +
	 * e.getMessage()); } }
	 */

	@Test
	public void prepareDockerApp() {
		try {
			javaGenericDockerPreparator.prepareDockerApp(outFolder1);
		} catch (AcumosServiceException e) {
			Assert.fail("prepareDockerApp failed : " + e.getMessage());
		}
	}

}
