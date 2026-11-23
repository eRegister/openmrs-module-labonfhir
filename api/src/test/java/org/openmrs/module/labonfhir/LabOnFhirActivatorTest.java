package org.openmrs.module.labonfhir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.openmrs.PatientIdentifierType;
import org.openmrs.api.PatientService;
import org.openmrs.module.fhir2.api.FhirPatientIdentifierSystemService;
import org.openmrs.module.fhir2.model.FhirPatientIdentifierSystem;

@RunWith(MockitoJUnitRunner.class)
public class LabOnFhirActivatorTest {

	private static final String PID_TYPE_UUID = "81433852-3f10-11e4-adec-0800271c1b75";

	private static final String SYSTEM_URL = "http://health.gov.ls/fhir/identifier/patient-identifier";

	@Mock
	private LabOnFhirConfig config;

	@Mock
	private PatientService patientService;

	@Mock
	private FhirPatientIdentifierSystemService fhirPatientIdentifierSystemService;

	@InjectMocks
	private LabOnFhirActivator activator;

	@Before
	public void setUp() {
		when(config.getPatientIdentifierUuid()).thenReturn(PID_TYPE_UUID);
		when(config.getLisIdentifierSystemUrl()).thenReturn(SYSTEM_URL);
		// As FHIR2 does: no system is registered for a null identifier type
		when(fhirPatientIdentifierSystemService.getFhirPatientIdentifierSystem(null)).thenReturn(Optional.empty());
	}

	@Test
	public void createFhirPatientIdentierSystem_shouldNotSaveWhenIdentifierTypeDoesNotExist() {
		when(patientService.getPatientIdentifierTypeByUuid(PID_TYPE_UUID)).thenReturn(null);

		activator.createFhirPatientIdentierSystem();

		verify(fhirPatientIdentifierSystemService, never()).saveFhirPatientIdentifierSystem(any(FhirPatientIdentifierSystem.class));
	}

	@Test
	public void createFhirPatientIdentierSystem_shouldNotSaveWhenIdentifierTypeUuidIsBlank() {
		when(config.getPatientIdentifierUuid()).thenReturn(" ");

		activator.createFhirPatientIdentierSystem();

		verify(fhirPatientIdentifierSystemService, never()).saveFhirPatientIdentifierSystem(any(FhirPatientIdentifierSystem.class));
	}

	@Test
	public void createFhirPatientIdentierSystem_shouldCreateSystemForExistingIdentifierType() {
		PatientIdentifierType pidType = new PatientIdentifierType();
		when(patientService.getPatientIdentifierTypeByUuid(PID_TYPE_UUID)).thenReturn(pidType);
		when(fhirPatientIdentifierSystemService.getFhirPatientIdentifierSystem(pidType)).thenReturn(Optional.empty());

		activator.createFhirPatientIdentierSystem();

		ArgumentCaptor<FhirPatientIdentifierSystem> saved = ArgumentCaptor.forClass(FhirPatientIdentifierSystem.class);
		verify(fhirPatientIdentifierSystemService).saveFhirPatientIdentifierSystem(saved.capture());
		assertThat(saved.getValue().getPatientIdentifierType(), sameInstance(pidType));
		assertThat(saved.getValue().getUrl(), equalTo(SYSTEM_URL));
	}

	@Test
	public void createFhirPatientIdentierSystem_shouldUpdateExistingSystem() {
		PatientIdentifierType pidType = new PatientIdentifierType();
		FhirPatientIdentifierSystem existing = new FhirPatientIdentifierSystem();
		existing.setUrl("http://openelis-global.org/pat_nationalId");
		when(patientService.getPatientIdentifierTypeByUuid(PID_TYPE_UUID)).thenReturn(pidType);
		when(fhirPatientIdentifierSystemService.getFhirPatientIdentifierSystem(pidType)).thenReturn(Optional.of(existing));

		activator.createFhirPatientIdentierSystem();

		verify(fhirPatientIdentifierSystemService).saveFhirPatientIdentifierSystem(existing);
		assertThat(existing.getUrl(), equalTo(SYSTEM_URL));
	}
}
