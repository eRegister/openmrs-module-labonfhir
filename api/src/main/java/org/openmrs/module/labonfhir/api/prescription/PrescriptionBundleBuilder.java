package org.openmrs.module.labonfhir.api.prescription;

import static org.openmrs.module.labonfhir.api.prescription.BonoloConcepts.ART_REGIMEN;
import static org.openmrs.module.labonfhir.api.prescription.BonoloConcepts.BONOLO_CLIENT;
import static org.openmrs.module.labonfhir.api.prescription.BonoloConcepts.DISPENSED;
import static org.openmrs.module.labonfhir.api.prescription.BonoloConcepts.EXT_ORIGINATING_FACILITY;
import static org.openmrs.module.labonfhir.api.prescription.BonoloConcepts.FACILITY_CODE_ATTRIBUTE_TYPE;
import static org.openmrs.module.labonfhir.api.prescription.BonoloConcepts.NEXT_PICKUP_DATE;
import static org.openmrs.module.labonfhir.api.prescription.BonoloConcepts.PATIENT_IDENTIFIER_SYSTEMS;
import static org.openmrs.module.labonfhir.api.prescription.BonoloConcepts.PICKUP_DISTRICT;
import static org.openmrs.module.labonfhir.api.prescription.BonoloConcepts.PICKUP_QUESTIONS;
import static org.openmrs.module.labonfhir.api.prescription.BonoloConcepts.PICKUP_TYPE;
import static org.openmrs.module.labonfhir.api.prescription.BonoloConcepts.PRIMARY_CONTACT_ATTRIBUTE_TYPE;
import static org.openmrs.module.labonfhir.api.prescription.BonoloConcepts.SYSTEM_CONCEPT;
import static org.openmrs.module.labonfhir.api.prescription.BonoloConcepts.SYSTEM_FACILITY_CODE;
import static org.openmrs.module.labonfhir.api.prescription.BonoloConcepts.SYSTEM_ORDER_NUMBER;
import static org.openmrs.module.labonfhir.api.prescription.BonoloConcepts.SYSTEM_PRESCRIPTION;
import static org.openmrs.module.labonfhir.api.prescription.BonoloConcepts.YES;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Dosage;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Medication;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.DrugOrder;
import org.openmrs.Encounter;
import org.openmrs.LocationAttribute;
import org.openmrs.Obs;
import org.openmrs.Order;
import org.openmrs.PersonAttribute;
import org.openmrs.api.ConceptService;
import org.openmrs.api.ObsService;
import org.openmrs.module.fhir2.api.FhirMedicationRequestService;
import org.openmrs.module.fhir2.api.FhirMedicationService;
import org.openmrs.module.fhir2.api.FhirPatientService;
import org.openmrs.module.fhir2.api.FhirPractitionerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Builds the transaction Bundle for one Bonolo Health prescription: all drug orders written in one OpenMRS
 * encounter. See the eRegister FHIR IG, Prescription Workflow (draft), for the agreed design.
 */
@Component
public class PrescriptionBundleBuilder {

	private static final Logger log = LoggerFactory.getLogger(PrescriptionBundleBuilder.class);

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private FhirPatientService patientService;

	@Autowired
	private FhirPractitionerService practitionerService;

	@Autowired
	private FhirMedicationService medicationService;

	@Autowired
	private FhirMedicationRequestService medicationRequestService;

	@Autowired
	private ObsService obsService;

	@Autowired
	private ConceptService conceptService;

	/** Either a bundle to send, or the reason nothing is sent for this encounter. */
	public static final class Result {

		private final Bundle bundle;

		private final String skipReason;

		private Result(Bundle bundle, String skipReason) {
			this.bundle = bundle;
			this.skipReason = skipReason;
		}

		static Result send(Bundle bundle) {
			return new Result(bundle, null);
		}

		static Result skip(String reason) {
			return new Result(null, reason);
		}

		public boolean hasBundle() {
			return bundle != null;
		}

		public Bundle getBundle() {
			return bundle;
		}

		public String getSkipReason() {
			return skipReason;
		}
	}

	public Result build(Encounter encounter) {
		if (encounter == null || encounter.getVoided()) {
			return Result.skip("encounter missing or voided");
		}

		List<DrugOrder> drugOrders = prescriptionLines(encounter);
		if (drugOrders.isEmpty()) {
			return Result.skip("no drug orders in encounter");
		}

		org.openmrs.Patient omrsPatient = encounter.getPatient();
		if (!isBonoloClient(omrsPatient)) {
			return Result.skip("patient is not a Bonolo Health client");
		}

		// The Bonolo answers must be recorded in the same encounter as the drug orders; older values are never used
		List<Obs> obs = encounter.getAllObs(false).stream().filter(o -> !o.getVoided()).collect(Collectors.toList());
		Optional<Obs> regimen = findObs(obs, ART_REGIMEN);
		Optional<Obs> nextPickup = findObs(obs, NEXT_PICKUP_DATE);
		Optional<Obs> pickupPoint = obs.stream()
		        .filter(o -> o.getConcept() != null && PICKUP_QUESTIONS.containsKey(o.getConcept().getUuid()))
		        .filter(o -> o.getValueCoded() != null).findFirst();
		List<String> missing = new ArrayList<>();
		if (!regimen.isPresent() || regimen.get().getValueCoded() == null) {
			missing.add("regimen");
		}
		if (!nextPickup.isPresent() || nextPickup.get().getValueDatetime() == null) {
			missing.add("next pickup date");
		}
		if (!pickupPoint.isPresent()) {
			missing.add("pickup point");
		}
		if (!missing.isEmpty()) {
			return Result.skip("waiting for Bonolo answers in the same encounter: " + String.join(", ", missing));
		}

		org.openmrs.Location facility = facilityOf(encounter.getLocation());
		String facilityCode = facilityCode(facility);
		if (facility == null || facilityCode == null) {
			return Result.skip("facility of encounter location has no IdentifierSourceName code");
		}

		Patient patient = patientService.get(omrsPatient.getUuid());
		if (patient == null) {
			return Result.skip("FHIR2 returned no Patient " + omrsPatient.getUuid());
		}
		filterPatient(patient, omrsPatient);

		Location location = buildFacility(facility, facilityCode);

		List<Observation> supporting = new ArrayList<>();
		supporting.add(codedObservation(regimen.get(), omrsPatient.getUuid()));
		supporting.add(nextPickupObservation(nextPickup.get(), omrsPatient.getUuid()));
		supporting.add(pickupPointObservation(pickupPoint.get(), omrsPatient.getUuid()));

		// Keyed by id so a practitioner or drug used by several lines is sent once
		Map<String, Resource> resources = new LinkedHashMap<>();
		add(resources, patient);
		add(resources, location);
		supporting.forEach(o -> add(resources, o));

		for (DrugOrder order : drugOrders) {
			MedicationRequest request = medicationRequestService.get(order.getUuid());
			if (request == null) {
				log.warn("FHIR2 returned no MedicationRequest for drug order {}; line not sent", order.getUuid());
				continue;
			}
			adaptMedicationRequest(request, order, encounter, location, supporting);
			add(resources, request);

			if (order.getDrug() != null) {
				Medication medication = medicationService.get(order.getDrug().getUuid());
				if (medication != null) {
					addConceptSystem(medication.getCode());
					add(resources, medication);
				}
			} else {
				log.warn("Drug order {} has no coded drug; MedicationRequest carries only a medication concept",
				    order.getUuid());
			}

			if (order.getOrderer() != null) {
				Practitioner practitioner = practitionerService.get(order.getOrderer().getUuid());
				if (practitioner != null) {
					add(resources, practitioner);
				}
			}
		}

		if (resources.values().stream().noneMatch(r -> r instanceof MedicationRequest)) {
			return Result.skip("no MedicationRequest could be built");
		}

		Bundle bundle = new Bundle();
		bundle.setType(Bundle.BundleType.TRANSACTION);
		for (Resource resource : resources.values()) {
			bundle.addEntry().setResource(resource).getRequest().setMethod(Bundle.HTTPVerb.PUT)
			        .setUrl(resource.fhirType() + "/" + resource.getIdElement().getIdPart());
		}
		return Result.send(bundle);
	}

	/** Drug orders of the encounter that are prescription lines (discontinue orders are not lines). */
	static List<DrugOrder> prescriptionLines(Encounter encounter) {
		return encounter.getOrders().stream().filter(o -> o instanceof DrugOrder).filter(o -> !o.getVoided())
		        .filter(o -> o.getAction() != Order.Action.DISCONTINUE).map(o -> (DrugOrder) o)
		        .collect(Collectors.toList());
	}

	boolean isBonoloClient(org.openmrs.Patient patient) {
		Concept question = conceptService.getConceptByUuid(BONOLO_CLIENT);
		if (question == null) {
			log.warn("Concept 'Bonolo Health Client' ({}) not found; no prescriptions are sent", BONOLO_CLIENT);
			return false;
		}
		// The patient's most recent answer decides
		return obsService.getObservationsByPersonAndConcept(patient, question).stream().filter(o -> !o.getVoided())
		        .filter(o -> o.getObsDatetime() != null).max(Comparator.comparing(Obs::getObsDatetime))
		        .map(o -> o.getValueCoded() != null && YES.equals(o.getValueCoded().getUuid())).orElse(false);
	}

	boolean isDispensed(DrugOrder order) {
		Concept dispensed = conceptService.getConceptByUuid(DISPENSED);
		Concept trueConcept = conceptService.getTrueConcept();
		if (dispensed == null || trueConcept == null) {
			return false;
		}
		return obsService.getObservationsByPersonAndConcept(order.getPatient(), dispensed).stream()
		        .filter(o -> !o.getVoided()).filter(o -> o.getOrder() != null)
		        .filter(o -> order.getUuid().equals(o.getOrder().getUuid()))
		        .anyMatch(o -> o.getValueCoded() != null && trueConcept.getUuid().equals(o.getValueCoded().getUuid()));
	}

	private static Optional<Obs> findObs(List<Obs> obs, String conceptUuid) {
		return obs.stream().filter(o -> o.getConcept() != null && conceptUuid.equals(o.getConcept().getUuid()))
		        .findFirst();
	}

	private static void add(Map<String, Resource> resources, Resource resource) {
		resources.put(resource.fhirType() + "/" + resource.getIdElement().getIdPart(), resource);
	}

	// --- Patient

	/** Keeps only the agreed identifiers (with systems), replaces telecom and removes OpenMRS extensions. */
	static void filterPatient(Patient patient, org.openmrs.Patient omrsPatient) {
		for (Iterator<Identifier> it = patient.getIdentifier().iterator(); it.hasNext();) {
			Identifier identifier = it.next();
			String typeUuid = identifier.hasType() ? identifier.getType().getCodingFirstRep().getCode() : null;
			String system = typeUuid == null ? null : PATIENT_IDENTIFIER_SYSTEMS.get(typeUuid);
			if (system == null) {
				it.remove();
			} else {
				identifier.setSystem(system);
				identifier.getExtension().clear();
			}
		}

		patient.getExtension().clear();

		patient.getTelecom().clear();
		for (PersonAttribute attribute : omrsPatient.getActiveAttributes()) {
			if (attribute.getAttributeType() != null
			        && PRIMARY_CONTACT_ATTRIBUTE_TYPE.equals(attribute.getAttributeType().getUuid())) {
				String number = StringUtils.trimToEmpty(attribute.getValue());
				if (number.matches("[0-9]{8}")) {
					patient.addTelecom().setSystem(ContactPoint.ContactPointSystem.PHONE)
					        .setUse(ContactPoint.ContactPointUse.MOBILE).setValue(number);
				} else {
					log.warn("Primary Contact of patient {} is not 8 digits; not sent", omrsPatient.getUuid());
				}
				break;
			}
		}
	}

	// --- Facility

	static org.openmrs.Location facilityOf(org.openmrs.Location encounterLocation) {
		if (encounterLocation == null) {
			return null;
		}
		return encounterLocation.getParentLocation() != null ? encounterLocation.getParentLocation()
		        : encounterLocation;
	}

	static String facilityCode(org.openmrs.Location facility) {
		if (facility == null) {
			return null;
		}
		for (LocationAttribute attribute : facility.getActiveAttributes()) {
			// The stored value: for this free-text attribute it is the code itself (getValue() needs a context)
			if (attribute.getAttributeType() != null
			        && FACILITY_CODE_ATTRIBUTE_TYPE.equals(attribute.getAttributeType().getUuid())
			        && StringUtils.isNotBlank(attribute.getValueReference())) {
				return attribute.getValueReference().trim();
			}
		}
		return null;
	}

	static Location buildFacility(org.openmrs.Location facility, String facilityCode) {
		Location location = new Location();
		location.setId(facility.getUuid());
		location.setName(facility.getName());
		location.setStatus(Location.LocationStatus.ACTIVE);
		location.addIdentifier().setSystem(SYSTEM_FACILITY_CODE).setValue(facilityCode);
		return location;
	}

	// --- MedicationRequest

	void adaptMedicationRequest(MedicationRequest request, DrugOrder order, Encounter encounter, Location facility,
	        List<Observation> supporting) {
		request.getIdentifier().removeIf(i -> SYSTEM_ORDER_NUMBER.equals(i.getSystem()));
		if (order.getOrderNumber() != null) {
			request.addIdentifier().setSystem(SYSTEM_ORDER_NUMBER).setValue(order.getOrderNumber());
		}
		request.setGroupIdentifier(new Identifier().setSystem(SYSTEM_PRESCRIPTION).setValue(encounter.getUuid()));

		// OpenMRS before 2.2 has no fulfiller status, so FHIR2 never reports 'completed': derive it from Dispensed
		if (isDispensed(order)) {
			request.setStatus(MedicationRequest.MedicationRequestStatus.COMPLETED);
		}

		request.setEncounter(null);

		request.getExtension().removeIf(e -> EXT_ORIGINATING_FACILITY.equals(e.getUrl()));
		request.addExtension(EXT_ORIGINATING_FACILITY, new Reference("Location/" + facility.getIdElement().getIdPart()));

		request.getSupportingInformation().clear();
		for (Observation observation : supporting) {
			request.addSupportingInformation(new Reference("Observation/" + observation.getIdElement().getIdPart()));
		}

		if (request.hasMedicationCodeableConcept()) { // drug orders without a coded drug
			addConceptSystem(request.getMedicationCodeableConcept());
		}

		Dosage dosage = request.getDosageInstructionFirstRep();
		DosageText text = dosageText(order);
		dosage.setText(text.text);
		dosage.getAdditionalInstruction().clear();
		if (text.additionalInstruction != null) {
			dosage.addAdditionalInstruction(new CodeableConcept().setText(text.additionalInstruction));
		}
	}

	/** Readable dosage text built from the drug order, and the additional instruction from Bahmni's JSON. */
	static final class DosageText {

		final String text;

		final String additionalInstruction;

		DosageText(String text, String additionalInstruction) {
			this.text = text;
			this.additionalInstruction = additionalInstruction;
		}
	}

	static DosageText dosageText(DrugOrder order) {
		JsonNode json = parseJson(order.getDosingInstructions());
		String unit = conceptName(order.getDoseUnits());

		List<String> parts = new ArrayList<>();
		if (order.getDose() != null) {
			parts.add(join(" ", formatNumber(order.getDose()), unit));
		} else if (json != null && (json.has("morningDose") || json.has("afternoonDose") || json.has("eveningDose"))) {
			// Bahmni variable dosing: morning-afternoon-evening
			parts.add(join(" ", formatNumber(json.path("morningDose").asDouble(0)) + "-"
			        + formatNumber(json.path("afternoonDose").asDouble(0)) + "-"
			        + formatNumber(json.path("eveningDose").asDouble(0)), unit));
		}
		if (order.getRoute() != null) {
			parts.add(conceptName(order.getRoute()));
		}
		if (order.getFrequency() != null && order.getFrequency().getConcept() != null) {
			parts.add(conceptName(order.getFrequency().getConcept()));
		}
		if (order.getDuration() != null) {
			parts.add("for " + join(" ", order.getDuration().toString(), conceptName(order.getDurationUnits())));
		}
		parts.removeIf(StringUtils::isBlank);

		String instructions = null;
		String additional = null;
		if (json != null) {
			instructions = textOrNull(json.get("instructions"));
			additional = textOrNull(json.get("additionalInstructions"));
		} else if (StringUtils.isNotBlank(order.getDosingInstructions())) {
			instructions = order.getDosingInstructions().trim(); // free-text dosing
		}

		String text = String.join(", ", parts);
		if (instructions != null) {
			text = text.isEmpty() ? instructions : text + ". " + instructions;
		}
		return new DosageText(text.isEmpty() ? null : text, additional);
	}

	private static JsonNode parseJson(String value) {
		if (value == null || !value.trim().startsWith("{")) {
			return null;
		}
		try {
			return JSON.readTree(value);
		}
		catch (Exception e) {
			return null;
		}
	}

	private static String textOrNull(JsonNode node) {
		return node == null || node.isNull() || StringUtils.isBlank(node.asText()) ? null : node.asText().trim();
	}

	private static String join(String separator, String... values) {
		List<String> nonBlank = new ArrayList<>();
		for (String v : values) {
			if (StringUtils.isNotBlank(v)) {
				nonBlank.add(v);
			}
		}
		return String.join(separator, nonBlank);
	}

	private static String formatNumber(Double value) {
		if (value == null) {
			return null;
		}
		return value == Math.rint(value) ? String.valueOf(value.longValue()) : value.toString();
	}

	// --- Observations

	Observation codedObservation(Obs obs, String patientUuid) {
		Observation observation = baseObservation(obs, patientUuid);
		observation.setValue(concept(obs.getValueCoded()));
		return observation;
	}

	Observation nextPickupObservation(Obs obs, String patientUuid) {
		Observation observation = baseObservation(obs, patientUuid);
		observation.setValue(new DateTimeType(obs.getValueDatetime()));
		return observation;
	}

	/** code = the pickup question answered (type and district), value = the site; components repeat both. */
	Observation pickupPointObservation(Obs obs, String patientUuid) {
		Observation observation = codedObservation(obs, patientUuid);
		BonoloConcepts.PickupQuestion question = PICKUP_QUESTIONS.get(obs.getConcept().getUuid());
		observation.addComponent().setCode(concept(PICKUP_TYPE, conceptService.getConceptByUuid(PICKUP_TYPE)))
		        .setValue(concept(question.typeUuid, conceptService.getConceptByUuid(question.typeUuid)));
		observation.addComponent()
		        .setCode(concept(PICKUP_DISTRICT, conceptService.getConceptByUuid(PICKUP_DISTRICT)))
		        .setValue(concept(question.districtUuid, conceptService.getConceptByUuid(question.districtUuid)));
		return observation;
	}

	private static Observation baseObservation(Obs obs, String patientUuid) {
		Observation observation = new Observation();
		observation.setId(obs.getUuid());
		observation.setStatus(Observation.ObservationStatus.FINAL);
		observation.setCode(concept(obs.getConcept()));
		observation.setSubject(new Reference("Patient/" + patientUuid));
		observation.setEffective(new DateTimeType(obs.getObsDatetime()));
		return observation;
	}

	// --- Concepts

	static CodeableConcept concept(Concept concept) {
		return concept(concept.getUuid(), concept);
	}

	static CodeableConcept concept(String uuid, Concept concept) {
		String name = conceptName(concept);
		CodeableConcept codeableConcept = new CodeableConcept();
		codeableConcept.addCoding(new Coding(SYSTEM_CONCEPT, uuid, name));
		codeableConcept.setText(name);
		return codeableConcept;
	}

	/** FHIR2 emits concept UUID codings without a system; give them the eRegister concept system. */
	static void addConceptSystem(CodeableConcept codeableConcept) {
		if (codeableConcept == null) {
			return;
		}
		for (Coding coding : codeableConcept.getCoding()) {
			if (!coding.hasSystem() && coding.hasCode()) {
				coding.setSystem(SYSTEM_CONCEPT);
			}
		}
	}

	/** English name without needing an OpenMRS context. */
	static String conceptName(Concept concept) {
		if (concept == null) {
			return null;
		}
		ConceptName name = concept.getName(Locale.ENGLISH);
		if (name == null) {
			name = concept.getNames().stream().filter(Objects::nonNull).findFirst().orElse(null);
		}
		return name == null ? null : name.getName();
	}
}
