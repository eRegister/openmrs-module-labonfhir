package org.openmrs.module.labonfhir.api.event;

import javax.jms.Message;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.api.Include;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.param.TokenAndListParam;
import ca.uhn.fhir.rest.param.TokenParam;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Task;
import org.openmrs.api.context.Daemon;
import org.openmrs.event.EventListener;
import org.openmrs.module.DaemonToken;
import org.openmrs.module.fhir2.api.FhirDiagnosticReportService;
import org.openmrs.module.fhir2.api.FhirLocationService;
import org.openmrs.module.fhir2.api.FhirObservationService;
import org.openmrs.module.fhir2.api.FhirServiceRequestService;
import org.openmrs.module.fhir2.api.FhirTaskService;
import org.openmrs.module.fhir2.api.util.FhirUtils;
import org.openmrs.module.labonfhir.LabOnFhirConfig;
import org.openmrs.module.labonfhir.api.model.FailedTask;
import org.openmrs.module.labonfhir.api.service.LabOnFhirService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

public abstract class LabCreationListener implements EventListener {

	private static final Logger log = LoggerFactory.getLogger(EncounterCreationListener.class);

	private DaemonToken daemonToken;

	@Autowired
	@Qualifier("labOrderFhirClient")
	private IGenericClient client;

	@Autowired
	private LabOnFhirConfig config;

	@Autowired
	@Qualifier("fhirR4")
	private FhirContext ctx;

	@Autowired
	FhirLocationService fhirLocationService ;

	@Autowired
	private FhirTaskService fhirTaskService;

	//The three services below are addded so that we can include supportinginfo obs linked in the service request
	@Autowired
	private FhirServiceRequestService fhirServiceRequestService;
	@Autowired
	private FhirObservationService fhirObservationService;

	@Autowired
	private FhirDiagnosticReportService fhirDiagnosticReportService;
	
	@Autowired
	private LabOnFhirService labOnFhirService ;

	public DaemonToken getDaemonToken() {
		return daemonToken;
	}

	public void setDaemonToken(DaemonToken daemonToken) {
		this.daemonToken = daemonToken;
	}

	@Override
	public void onMessage(Message message) {
		log.trace("Received message {}", message);

		Daemon.runInDaemonThread(() -> {
			try {
				processMessage(message);
			}
			catch (Exception e) {
				log.error("Failed to update the user's last viewed patients property", e);
			}
		}, daemonToken);
	}

	public abstract void processMessage(Message message);

	public Bundle createLabBundle(Task task) {
		TokenAndListParam uuid = new TokenAndListParam().addAnd(new TokenParam(task.getIdElement().getIdPart()));
		HashSet<Include> includes = new HashSet<>();
		includes.add(new Include("Task:patient"));
		includes.add(new Include("Task:owner"));
		includes.add(new Include("Task:encounter"));
		includes.add(new Include("Task:based-on"));

		IBundleProvider labBundle = fhirTaskService.searchForTasks(null, null, null, uuid, null, null, includes);

		Bundle transactionBundle = new Bundle();
		transactionBundle.setType(Bundle.BundleType.TRANSACTION);
		List<IBaseResource> labResources = labBundle.getAllResources();
		
		//Add requisition id (Added as an identifier on Task resource) to serviceRequest(s) in the bundle
		//labResources = insertRequisitionIdOnServReq(labResources);
         
		if (!task.getLocation().isEmpty() && config.getLabUpdateTriggerObject().equals("Encounter")) {
			labResources.add(fhirLocationService.get(FhirUtils.referenceToId(task.getLocation().getReference()).get()));
		}
    
		// Add ART Regimen, Pregnancy status, etc. Obs & DiagnosticReport (including Obs linked in DiagReport) linked on ServiceRequest
        //(1) get task based on -- servicerequest
		//(2) then get supporting info
		//(3) reference to id for each item
		//(4) Pull obs, add to lab bundle
		if(!task.getBasedOn().isEmpty()){
			List <Reference> taskReferences = task.getBasedOn();
			List <String> processedReferences = new ArrayList<String>();
			for(Reference taskReference : taskReferences){
				if(taskReference.getType().equals("ServiceRequest")){
					ServiceRequest serviceRequest = fhirServiceRequestService.get(FhirUtils.referenceToId(taskReference.getReference()).get());
					List<Reference> serviceRequestReferences = serviceRequest.getSupportingInfo();
					DateTimeType currRegimenStartDate = null;
                    for(Reference serviceRequestReference: serviceRequestReferences){
						String resourceId = FhirUtils.referenceToId(serviceRequestReference.getReference()).get();
						String refDisplay = serviceRequestReference.getDisplay();
						if(!resourceId.equals("null") && !processedReferences.contains(resourceId)){ //exclude null (for resources that don't exist) and avoid re-adds to the bundle
							if(serviceRequestReference.getType().equals("Observation")){
								Observation obsToAdd = fhirObservationService.get(resourceId);
					
								if(refDisplay.equals("Current Regimen")){
									//grab effective date & set additional disa param code
									currRegimenStartDate = obsToAdd.getEffectiveDateTimeType();
									CodeableConcept obsCode = obsToAdd.getCode();
									obsCode.addCoding(getDISACodingFor("Current Treatment", "CTREA"));
									obsToAdd.setCode(obsCode);
								} 
								else if(refDisplay.equals("Current Regimen startdate")){
									//override date
									if(currRegimenStartDate != null){
										obsToAdd.setValue(currRegimenStartDate);
									}
								}
								else if(refDisplay.contains("Previous Regimen")){
									//add another disa code
									CodeableConcept obsCode = obsToAdd.getCode();
									obsCode.addCoding(getDISACodingFor("Previous Treatment", "PTREA"));
									obsToAdd.setCode(obsCode);
								}
								else if(refDisplay.equals("Prev VL Results")){
									//add another disa code
									CodeableConcept obsCode = obsToAdd.getCode();
									obsCode.addCoding(getDISACodingFor("Previous VL Results", "PVLD"));
									obsToAdd.setCode(obsCode);
								}
								else if(refDisplay.equals("First CD4")){
									//add another disa code
									CodeableConcept obsCode = obsToAdd.getCode();
									obsCode.addCoding(getDISACodingFor("First CD4", "FCD4"));
									obsToAdd.setCode(obsCode);
								}
								else if(refDisplay.equals("Last CD4")){
									//add another disa code -- skip when 1st & last regimen are the same
									CodeableConcept obsCode = obsToAdd.getCode();
									obsCode.addCoding(getDISACodingFor("Last CD4", "LCD4"));
									obsToAdd.setCode(obsCode);
								}

								labResources.add(obsToAdd);
								processedReferences.add(resourceId);
							}
							else if(serviceRequestReference.getType().equals("DiagnosticReport")){
								//labResources.add(fhirDiagnosticReportService.get(resourceId));
								TokenAndListParam diagReportUuid = new TokenAndListParam().addAnd(new TokenParam(resourceId));
								HashSet<Include> includes_ = new HashSet<>();
								includes_.add(new Include("DiagnosticReport:result"));
								IBundleProvider diagReportBundle = fhirDiagnosticReportService.searchForDiagnosticReports(null, null, null, null, null, diagReportUuid, null, null, includes_);
                                List <IBaseResource> diagReportResources = diagReportBundle.getAllResources();
								for (IBaseResource diagResource : diagReportResources){
									labResources.add(diagResource);
								}
								processedReferences.add(resourceId);
							}
							else{
								log.error("How did we get here?? **************************");
								log.error("Found an unhandled reference ... expecting an Observation or DiagnosticReport reference.");
							}
						}
					}
				}
			}
		}

		for (IBaseResource r : labResources) {
			Resource resource = (Resource) r;
			Bundle.BundleEntryComponent component = transactionBundle.addEntry();
			component.setResource(resource);
			component.getRequest().setUrl(resource.fhirType() + "/" + resource.getIdElement().getIdPart())
			        .setMethod(Bundle.HTTPVerb.PUT);

		}
		return transactionBundle;
	}



	

	private Coding getDISACodingFor(String name, String code) {
		String url = "http://health.gov.ls/laboratory-services";
        return new Coding(url, code, name);
    }

	protected void sendTask(Task task) {
		if (task != null) {
			if (config.getActivateFhirPush()) {
				Bundle labBundle = createLabBundle(task);
				try {
					client.transaction().withBundle(labBundle).execute();
				}
				catch (Exception e) {
					saveFailedTask(task.getIdElement().getIdPart(), e.getMessage());
					log.error("Failed to send Task with UUID " + task.getIdElement().getIdPart(), e);
				}
				log.debug(ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(labBundle));
			}
		}
	}

	private List<IBaseResource> insertRequisitionIdOnServReq(List<IBaseResource> labResources){
		List <Identifier> taskIdentifiers = new ArrayList<>();
		List <IBaseResource> updatedLabResources = new ArrayList<>();
		Task taskResource = null;
		for (IBaseResource r : labResources){
			Resource resource = (Resource) r;
			if(resource instanceof Task){
				taskResource = (Task) resource;
				break;
			}
		}
        
		if(taskResource != null){
			//Grab Identifier
			taskIdentifiers = taskResource.getIdentifier();
			Identifier requisitionIdentifier = null;
			for(Identifier identifier : taskIdentifiers){
				if(identifier.getSystem() == "eRegister Lab Order Number"){
					requisitionIdentifier = identifier;
					break;
				}
			}
            
			//Add Task to list
			updatedLabResources.add(taskResource);
			//Add this identifier as requisition Id on All service requests in the list
            for (IBaseResource r : labResources){
				Resource resource = (Resource) r;
				if(resource instanceof ServiceRequest){
					ServiceRequest serviceRequestResource = (ServiceRequest) resource;
					serviceRequestResource.setRequisition(requisitionIdentifier);
					updatedLabResources.add(serviceRequestResource);
				} else {
					if(resource instanceof Task){
						//skip -- The mother Task resource is alrady in the list
					} else { //leave all other resources alone (e.g. Patient, Encounter, etc.)
						updatedLabResources.add(resource);
					}
				}
			}
	
		} else { //for some reason There is no Task resource in the bundle ... then don't modify the list
			updatedLabResources = labResources;
		}
		return updatedLabResources;
	}
	private void saveFailedTask(String taskUuid ,String error) {
		FailedTask failedTask = new FailedTask();
		failedTask.setError(error);
		failedTask.setIsSent(false);
		failedTask.setTaskUuid(taskUuid);
		labOnFhirService.saveOrUpdateFailedTask(failedTask);
	}
}
