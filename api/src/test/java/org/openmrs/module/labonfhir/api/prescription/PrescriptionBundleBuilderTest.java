package org.openmrs.module.labonfhir.api.prescription;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Medication;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.Drug;
import org.openmrs.DrugOrder;
import org.openmrs.Encounter;
import org.openmrs.LocationAttribute;
import org.openmrs.LocationAttributeType;
import org.openmrs.Obs;
import org.openmrs.Order;
import org.openmrs.OrderFrequency;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.Provider;
import org.openmrs.api.ConceptNameType;
import org.openmrs.api.ConceptService;
import org.openmrs.api.ObsService;
import org.openmrs.module.fhir2.api.FhirMedicationRequestService;
import org.openmrs.module.fhir2.api.FhirMedicationService;
import org.openmrs.module.fhir2.api.FhirPatientService;
import org.openmrs.module.fhir2.api.FhirPractitionerService;

@RunWith(MockitoJUnitRunner.class)
public class PrescriptionBundleBuilderTest {

	private static final String PATIENT_UUID = "11111111-0000-4000-8000-000000000001";

	private static final String ENCOUNTER_UUID = "22222222-0000-4000-8000-000000000002";

	private static final String ORDER_UUID = "33333333-0000-4000-8000-000000000003";

	private static final String DRUG_UUID = "189a5fc2-d29b-4ce5-b3ca-dc5405228bfc";

	private static final String DRUG_CONCEPT_UUID = "9d155660-c16e-42d8-bff1-76cebe867e56";

	private static final String PROVIDER_UUID = "44444444-0000-4000-8000-000000000004";

	private static final String FACILITY_UUID = "55555555-0000-4000-8000-000000000005";

	private static final String E_LOCKER_MASERU = "cfee8964-e9b0-4a09-8ad7-fb48bd643a46";

	private static final String PIONEER_MALL = "bb21afd5-ebba-4efb-b3e1-bd61e8c90c6f";

	private static final String FILE_NUMBER_TYPE = "d2084c23-edae-4b0f-98a2-cbcdb6708294";

	private static final String PATIENT_IDENTIFIER_TYPE = "81433852-3f10-11e4-adec-0800271c1b75";

	@Mock
	private FhirPatientService patientService;

	@Mock
	private FhirPractitionerService practitionerService;

	@Mock
	private FhirMedicationService medicationService;

	@Mock
	private FhirMedicationRequestService medicationRequestService;

	@Mock
	private ObsService obsService;

	@Mock
	private ConceptService conceptService;

	@InjectMocks
	private PrescriptionBundleBuilder builder;

	private org.openmrs.Patient patient;

	private Encounter encounter;

	private DrugOrder order;

	private Concept trueConcept;

	private Concept dispensedConcept;

	private final List<Obs> encounterObs = new ArrayList<>();

	static Concept concept(String uuid, String name) {
		Concept concept = new Concept();
		concept.setUuid(uuid);
		ConceptName conceptName = new ConceptName(name, Locale.ENGLISH);
		conceptName.setConceptNameType(ConceptNameType.FULLY_SPECIFIED);
		conceptName.setLocalePreferred(true);
		concept.setNames(new HashSet<>(Collections.singletonList(conceptName)));
		return concept;
	}

	private Obs obs(String conceptUuid, String name) {
		Obs obs = new Obs();
		obs.setUuid("obs-" + conceptUuid);
		obs.setConcept(concept(conceptUuid, name));
		obs.setObsDatetime(new Date());
		obs.setPerson(patient);
		obs.setVoided(false);
		return obs;
	}

	/** Order.setOrderNumber is not public in OpenMRS 2.0.5 (OrderService assigns it). */
	static void setOrderNumber(Order order, String orderNumber) {
		try {
			java.lang.reflect.Field field = Order.class.getDeclaredField("orderNumber");
			field.setAccessible(true);
			field.set(order, orderNumber);
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	@Before
	public void setUp() throws Exception {
		patient = new org.openmrs.Patient();
		patient.setUuid(PATIENT_UUID);
		PersonAttributeType phoneType = new PersonAttributeType();
		phoneType.setUuid(BonoloConcepts.PRIMARY_CONTACT_ATTRIBUTE_TYPE);
		PersonAttribute phone = new PersonAttribute(phoneType, "50000000");
		phone.setVoided(false);
		patient.setAttributes(new HashSet<>(Collections.singletonList(phone)));

		// Facility = parent of the encounter location, with its IdentifierSourceName code
		org.openmrs.Location facility = new org.openmrs.Location();
		facility.setUuid(FACILITY_UUID);
		facility.setName("Example Health Centre");
		LocationAttributeType codeType = new LocationAttributeType();
		codeType.setUuid(BonoloConcepts.FACILITY_CODE_ATTRIBUTE_TYPE);
		LocationAttribute code = new LocationAttribute();
		code.setAttributeType(codeType);
		code.setValueReferenceInternal("X000");
		code.setVoided(false);
		facility.addAttribute(code);
		org.openmrs.Location room = new org.openmrs.Location();
		room.setUuid("room-uuid");
		room.setParentLocation(facility);

		encounter = new Encounter();
		encounter.setUuid(ENCOUNTER_UUID);
		encounter.setPatient(patient);
		encounter.setLocation(room);

		Drug drug = new Drug();
		drug.setUuid(DRUG_UUID);
		Provider provider = new Provider();
		provider.setUuid(PROVIDER_UUID);
		OrderFrequency frequency = new OrderFrequency();
		frequency.setConcept(concept("freq", "Once a day"));

		order = new DrugOrder();
		order.setUuid(ORDER_UUID);
		setOrderNumber(order, "ORD-0001");
		order.setAction(Order.Action.NEW);
		order.setPatient(patient);
		order.setEncounter(encounter);
		order.setDrug(drug);
		order.setOrderer(provider);
		order.setDose(1.0);
		order.setDoseUnits(concept("unit", "Tablet(s)"));
		order.setRoute(concept("route", "Oral"));
		order.setFrequency(frequency);
		order.setDuration(90);
		order.setDurationUnits(concept("days", "Days"));
		order.setDosingInstructions("{\"instructions\":\"Before meals\",\"additionalInstructions\":\"Take with water\"}");
		encounter.setOrders(new HashSet<>(Collections.singletonList(order)));

		// Bonolo answers in the same encounter
		Obs regimen = obs(BonoloConcepts.ART_REGIMEN, "HIVTC, ART Regimen");
		regimen.setValueCoded(concept("225b0d93-d4b9-46b0-bbb2-1bce82c9107c", "1j=TDF-3TC-DTG"));
		Obs nextPickup = obs(BonoloConcepts.NEXT_PICKUP_DATE, "HIVTC, Bonolo Health next collection date");
		nextPickup.setValueDatetime(new Date());
		Obs pickup = obs(E_LOCKER_MASERU, "E-locker Pickup Location Maseru");
		pickup.setValueCoded(concept(PIONEER_MALL, "Pioneer Mall"));
		encounterObs.addAll(Arrays.asList(regimen, nextPickup, pickup));
		encounter.setObs(new HashSet<>(encounterObs));

		// Bonolo client = Yes
		Concept bonoloClient = concept(BonoloConcepts.BONOLO_CLIENT, "Bonolo Health Client");
		Obs bonolo = obs(BonoloConcepts.BONOLO_CLIENT, "Bonolo Health Client");
		bonolo.setValueCoded(concept(BonoloConcepts.YES, "Yes"));
		when(conceptService.getConceptByUuid(BonoloConcepts.BONOLO_CLIENT)).thenReturn(bonoloClient);
		when(obsService.getObservationsByPersonAndConcept(patient, bonoloClient))
		        .thenReturn(Collections.singletonList(bonolo));

		trueConcept = concept("true-uuid", "True");
		dispensedConcept = concept(BonoloConcepts.DISPENSED, "Dispensed");
		when(conceptService.getTrueConcept()).thenReturn(trueConcept);
		when(conceptService.getConceptByUuid(BonoloConcepts.DISPENSED)).thenReturn(dispensedConcept);

		// What FHIR2 returns
		when(patientService.get(PATIENT_UUID)).thenAnswer(i -> fhirPatient());
		when(medicationRequestService.get(ORDER_UUID)).thenAnswer(i -> fhirMedicationRequest());
		Medication medication = new Medication();
		medication.setId(DRUG_UUID);
		medication.getCode().addCoding().setCode(DRUG_CONCEPT_UUID);
		when(medicationService.get(DRUG_UUID)).thenReturn(medication);
		Practitioner practitioner = new Practitioner();
		practitioner.setId(PROVIDER_UUID);
		when(practitionerService.get(PROVIDER_UUID)).thenReturn(practitioner);
	}

	private static Patient fhirPatient() {
		Patient p = new Patient();
		p.setId(PATIENT_UUID);
		p.addIdentifier().setValue("B10110000001").setType(new CodeableConcept(new Coding().setCode(PATIENT_IDENTIFIER_TYPE)));
		p.addIdentifier().setValue("0001").setType(new CodeableConcept(new Coding().setCode(FILE_NUMBER_TYPE)));
		p.addExtension(new Extension("http://fhir.openmrs.org/ext/person-attribute"));
		p.addTelecom().setValue("12345");
		p.addName().setFamily("EXAMPLE");
		return p;
	}

	private static MedicationRequest fhirMedicationRequest() {
		MedicationRequest r = new MedicationRequest();
		r.setId(ORDER_UUID);
		r.setStatus(MedicationRequest.MedicationRequestStatus.ACTIVE);
		r.setMedication(new Reference("Medication/" + DRUG_UUID));
		r.setSubject(new Reference("Patient/" + PATIENT_UUID));
		r.setEncounter(new Reference("Encounter/" + ENCOUNTER_UUID));
		r.setRequester(new Reference("Practitioner/" + PROVIDER_UUID));
		r.addDosageInstruction().setText("{\"instructions\":\"Before meals\",\"additionalInstructions\":\"Take with water\"}");
		return r;
	}

	@SuppressWarnings("unchecked")
	private static <T extends Resource> List<T> ofType(Bundle bundle, Class<T> type) {
		return bundle.getEntry().stream().map(Bundle.BundleEntryComponent::getResource).filter(type::isInstance)
		        .map(r -> (T) r).collect(Collectors.toList());
	}

	@Test
	public void build_shouldBuildTheAgreedBundle() {
		PrescriptionBundleBuilder.Result result = builder.build(encounter);

		assertThat(result.getSkipReason(), result.hasBundle(), is(true));
		Bundle bundle = result.getBundle();
		assertThat(bundle.getType(), equalTo(Bundle.BundleType.TRANSACTION));
		assertThat(bundle.getEntry(), hasSize(8));
		for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
			assertThat(entry.getRequest().getMethod(), equalTo(Bundle.HTTPVerb.PUT));
			assertThat(entry.getRequest().getUrl(),
			    equalTo(entry.getResource().fhirType() + "/" + entry.getResource().getIdElement().getIdPart()));
		}

		Patient p = ofType(bundle, Patient.class).get(0);
		assertThat(p.getIdentifier(), hasSize(1));
		Identifier id = p.getIdentifierFirstRep();
		assertThat(id.getSystem(), equalTo("http://health.gov.ls/fhir/identifier/patient-identifier"));
		assertThat(p.getExtension(), hasSize(0));
		assertThat(p.getTelecom(), hasSize(1));
		assertThat(p.getTelecomFirstRep().getValue(), equalTo("50000000"));
		assertThat(p.getTelecomFirstRep().getSystem(), equalTo(ContactPoint.ContactPointSystem.PHONE));
		assertThat(p.getTelecomFirstRep().getUse(), equalTo(ContactPoint.ContactPointUse.MOBILE));

		Location location = ofType(bundle, Location.class).get(0);
		assertThat(location.getIdElement().getIdPart(), equalTo(FACILITY_UUID));
		assertThat(location.getIdentifierFirstRep().getSystem(), equalTo(BonoloConcepts.SYSTEM_FACILITY_CODE));
		assertThat(location.getIdentifierFirstRep().getValue(), equalTo("X000"));

		MedicationRequest request = ofType(bundle, MedicationRequest.class).get(0);
		assertThat(request.getIdentifierFirstRep().getSystem(), equalTo(BonoloConcepts.SYSTEM_ORDER_NUMBER));
		assertThat(request.getIdentifierFirstRep().getValue(), equalTo("ORD-0001"));
		assertThat(request.getGroupIdentifier().getSystem(), equalTo(BonoloConcepts.SYSTEM_PRESCRIPTION));
		assertThat(request.getGroupIdentifier().getValue(), equalTo(ENCOUNTER_UUID));
		assertThat(request.hasEncounter(), is(false));
		assertThat(request.getStatus(), equalTo(MedicationRequest.MedicationRequestStatus.ACTIVE));
		assertThat(((Reference) request.getExtensionByUrl(BonoloConcepts.EXT_ORIGINATING_FACILITY).getValue())
		        .getReference(), equalTo("Location/" + FACILITY_UUID));
		assertThat(request.getSupportingInformation(), hasSize(3));
		assertThat(request.getDosageInstructionFirstRep().getText(),
		    equalTo("1 Tablet(s), Oral, Once a day, for 90 Days. Before meals"));
		assertThat(request.getDosageInstructionFirstRep().getAdditionalInstructionFirstRep().getText(),
		    equalTo("Take with water"));

		Medication medication = ofType(bundle, Medication.class).get(0);
		assertThat(medication.getCode().getCodingFirstRep().getSystem(), equalTo(BonoloConcepts.SYSTEM_CONCEPT));

		List<Observation> observations = ofType(bundle, Observation.class);
		assertThat(observations, hasSize(3));
		Observation pickup = observations.stream()
		        .filter(o -> E_LOCKER_MASERU.equals(o.getCode().getCodingFirstRep().getCode())).findFirst().get();
		assertThat(pickup.getValueCodeableConcept().getCodingFirstRep().getCode(), equalTo(PIONEER_MALL));
		assertThat(pickup.getComponent(), hasSize(2));
		assertThat(pickup.getComponent().get(0).getValueCodeableConcept().getCodingFirstRep().getCode(),
		    equalTo(BonoloConcepts.TYPE_E_LOCKER));
		assertThat(pickup.getComponent().get(1).getValueCodeableConcept().getCodingFirstRep().getCode(),
		    equalTo(BonoloConcepts.DISTRICT_MASERU));
	}

	@Test
	public void build_shouldReportCompletedWhenTheOrderIsDispensed() {
		Obs dispensed = obs(BonoloConcepts.DISPENSED, "Dispensed");
		dispensed.setOrder(order);
		dispensed.setValueCoded(trueConcept);
		when(obsService.getObservationsByPersonAndConcept(patient, dispensedConcept))
		        .thenReturn(Collections.singletonList(dispensed));

		Bundle bundle = builder.build(encounter).getBundle();

		assertThat(ofType(bundle, MedicationRequest.class).get(0).getStatus(),
		    equalTo(MedicationRequest.MedicationRequestStatus.COMPLETED));
	}

	@Test
	public void build_shouldSkipPatientsWhoAreNotBonoloClients() {
		Concept bonoloClient = conceptService.getConceptByUuid(BonoloConcepts.BONOLO_CLIENT);
		Obs no = obs(BonoloConcepts.BONOLO_CLIENT, "Bonolo Health Client");
		no.setValueCoded(concept("b497171e-0410-4d8d-bbd4-7e1a8f8b504e", "No"));
		when(obsService.getObservationsByPersonAndConcept(patient, bonoloClient))
		        .thenReturn(Collections.singletonList(no));

		PrescriptionBundleBuilder.Result result = builder.build(encounter);

		assertThat(result.hasBundle(), is(false));
		assertThat(result.getSkipReason(), containsString("not a Bonolo"));
	}

	@Test
	public void build_shouldWaitWhenAPickupPointIsMissingFromTheEncounter() {
		encounter.setObs(new HashSet<>(encounterObs.subList(0, 2)));

		PrescriptionBundleBuilder.Result result = builder.build(encounter);

		assertThat(result.hasBundle(), is(false));
		assertThat(result.getSkipReason(), containsString("pickup point"));
	}

	@Test
	public void build_shouldSkipWhenTheFacilityHasNoCode() {
		encounter.getLocation().getParentLocation().getActiveAttributes().forEach(a -> a.setVoided(true));

		PrescriptionBundleBuilder.Result result = builder.build(encounter);

		assertThat(result.hasBundle(), is(false));
		assertThat(result.getSkipReason(), containsString("IdentifierSourceName"));
	}

	@Test
	public void build_shouldSkipEncountersWithOnlyDiscontinueOrders() {
		order.setAction(Order.Action.DISCONTINUE);

		assertThat(builder.build(encounter).getSkipReason(), containsString("no drug orders"));
	}

	@Test
	public void dosageText_shouldFormatBahmniVariableDosing() {
		DrugOrder variable = new DrugOrder();
		variable.setDoseUnits(concept("unit", "Tablet(s)"));
		variable.setDuration(30);
		variable.setDurationUnits(concept("days", "Days"));
		variable.setDosingInstructions("{\"morningDose\":1,\"afternoonDose\":0,\"eveningDose\":0.5}");

		PrescriptionBundleBuilder.DosageText text = PrescriptionBundleBuilder.dosageText(variable);

		assertThat(text.text, equalTo("1-0-0.5 Tablet(s), for 30 Days"));
		assertThat(text.additionalInstruction, nullValue());
	}

	@Test
	public void dosageText_shouldKeepFreeTextInstructions() {
		DrugOrder freeText = new DrugOrder();
		freeText.setDosingInstructions("Two puffs when needed");

		assertThat(PrescriptionBundleBuilder.dosageText(freeText).text, equalTo("Two puffs when needed"));
	}
}
