package org.openmrs.module.labonfhir.api.prescription;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Concepts, identifier types and FHIR systems used by the Bonolo Health prescription push. Concept UUIDs are from
 * the eRegister concept dictionary (release of 2026-11-06). The http://health.gov.ls/fhir/... URLs are proposed in
 * the eRegister FHIR IG (Prescription Workflow, draft) and still need Lesotho MoH confirmation.
 */
public final class BonoloConcepts {

	private BonoloConcepts() {
	}

	// --- FHIR systems
	public static final String SYSTEM_CONCEPT = "http://health.gov.ls/fhir/CodeSystem/eregister-concept";

	public static final String SYSTEM_PRESCRIPTION = "http://health.gov.ls/fhir/identifier/prescription";

	public static final String SYSTEM_ORDER_NUMBER = "http://health.gov.ls/fhir/identifier/order-number";

	public static final String SYSTEM_FACILITY_CODE = "http://health.gov.ls/fhir/identifier/facility-code";

	public static final String EXT_ORIGINATING_FACILITY =
	        "https://lesotho-eregister-v1.github.io/eregister-fhir-ig/StructureDefinition/originating-facility";

	// --- Patient identifier types sent (type UUID -> FHIR system); all others are dropped
	public static final Map<String, String> PATIENT_IDENTIFIER_SYSTEMS;

	static {
		Map<String, String> systems = new LinkedHashMap<>();
		systems.put("81433852-3f10-11e4-adec-0800271c1b75", "http://health.gov.ls/fhir/identifier/patient-identifier");
		systems.put("0d2ac572-8de3-46c8-9976-1f78899c599f", "http://health.gov.ls/fhir/identifier/national-id");
		systems.put("5c1e8ae2-33c6-11e8-8236-0242ac110002", "http://health.gov.ls/fhir/identifier/hiv-program-id");
		systems.put("d778362c-9d6a-4f97-bb5d-e3ea89580742", "http://health.gov.ls/fhir/identifier/new-hiv-program-id");
		PATIENT_IDENTIFIER_SYSTEMS = Collections.unmodifiableMap(systems);
	}

	/** Person attribute "telephoneNumber", labelled "Primary Contact" in Bahmni registration. */
	public static final String PRIMARY_CONTACT_ATTRIBUTE_TYPE = "c66d5786-60f1-4cdf-8292-77e601c007cb";

	/** Location attribute "IdentifierSourceName": the facility code, on the facility (parent) location. */
	public static final String FACILITY_CODE_ATTRIBUTE_TYPE = "c1e403a0-3f10-11e4-adec-0800271c1b75";

	// --- Bonolo Health form concepts
	public static final String BONOLO_CLIENT = "c8866aaf-4626-4d86-8667-d21e906d0b6e";

	public static final String YES = "a2065636-5326-40f5-aed6-0cc2cca81ccc";

	public static final String ART_REGIMEN = "13382e01-9f18-488b-b2d2-58ab54c82d82";

	public static final String NEXT_PICKUP_DATE = "9f2696bd-9c81-4ee8-9844-e6c77cd19e03";

	public static final String PICKUP_TYPE = "c128a7a7-45e9-4313-a49a-75b67b060456";

	public static final String PICKUP_DISTRICT = "79186178-1fed-4645-9d12-bfac0956ac56";

	/** Bahmni order attribute "Dispensed" (Boolean), saved as an obs linked to the drug order. */
	public static final String DISPENSED = "ff0d6d6a-e276-11e4-900f-080027b662ec";

	// Pickup types (answers of PICKUP_TYPE)
	static final String TYPE_E_LOCKER = "59b6f57b-770c-4168-9a79-f910dd6cb19f";

	static final String TYPE_RETAIL_PHARMACY = "31fef719-4519-4e20-8fc4-37012d90a2e8";

	static final String TYPE_OUTREACH = "3f2d9548-49a1-4f63-80e2-7e072966e97f";

	// Pickup districts (answers of PICKUP_DISTRICT)
	static final String DISTRICT_MASERU = "bd84b856-7699-49d9-a8cf-baad451d54d1";

	static final String DISTRICT_MAFETENG = "3c26267d-e290-4006-ad56-f0de1a5518df";

	static final String DISTRICT_MOHALES_HOEK = "44179144-9a35-4fd7-92f5-de9d5801ac7b";

	/** A pickup-point question's pickup type and district. */
	public static final class PickupQuestion {

		public final String typeUuid;

		public final String districtUuid;

		PickupQuestion(String typeUuid, String districtUuid) {
			this.typeUuid = typeUuid;
			this.districtUuid = districtUuid;
		}
	}

	/** The nine pickup-point questions (question UUID -> type and district). Their answer is the pickup site. */
	public static final Map<String, PickupQuestion> PICKUP_QUESTIONS;

	static {
		Map<String, PickupQuestion> q = new LinkedHashMap<>();
		q.put("cfee8964-e9b0-4a09-8ad7-fb48bd643a46", new PickupQuestion(TYPE_E_LOCKER, DISTRICT_MASERU));
		q.put("f1e0d69f-130e-41c3-8523-478ed5d889bb", new PickupQuestion(TYPE_E_LOCKER, DISTRICT_MAFETENG));
		q.put("2c3e0e68-4756-48a8-9fb4-6c1a61fd2f1a", new PickupQuestion(TYPE_E_LOCKER, DISTRICT_MOHALES_HOEK));
		q.put("636f1d68-cd13-47e9-a5d1-44d54b53e88d", new PickupQuestion(TYPE_RETAIL_PHARMACY, DISTRICT_MASERU));
		q.put("96d64908-3423-4c3b-b8a1-c57fac8968bd", new PickupQuestion(TYPE_RETAIL_PHARMACY, DISTRICT_MAFETENG));
		q.put("87377aaa-f52a-45fb-90c5-2c3788cc440a", new PickupQuestion(TYPE_RETAIL_PHARMACY, DISTRICT_MOHALES_HOEK));
		q.put("1100ca2c-b9d1-4b94-9a9e-ece592c3cd3e", new PickupQuestion(TYPE_OUTREACH, DISTRICT_MASERU));
		q.put("f9d5dadf-1d49-4d75-b9e0-820c2ccce34c", new PickupQuestion(TYPE_OUTREACH, DISTRICT_MAFETENG));
		q.put("d65b1f46-244a-4ac0-9614-567119bf01c5", new PickupQuestion(TYPE_OUTREACH, DISTRICT_MOHALES_HOEK));
		PICKUP_QUESTIONS = Collections.unmodifiableMap(q);
	}
}
