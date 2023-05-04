package org.openmrs.module.labonfhir.api.event;

import javax.jms.JMSException;
import javax.jms.MapMessage;
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
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Task;
import org.openmrs.Encounter;
import org.openmrs.api.APIException;
import org.openmrs.api.EncounterService;
import org.openmrs.api.context.Daemon;
import org.openmrs.event.EventListener;
import org.openmrs.module.DaemonToken;
import org.openmrs.module.fhir2.api.FhirLocationService;
import org.openmrs.module.fhir2.api.FhirObservationService;
import org.openmrs.module.fhir2.api.FhirServiceRequestService;
import org.openmrs.module.fhir2.api.FhirTaskService;
import org.openmrs.module.fhir2.api.util.FhirUtils;
import org.openmrs.module.labonfhir.LabOnFhirConfig;
import org.openmrs.module.labonfhir.api.LabOrderHandler;
import org.openmrs.module.labonfhir.api.fhir.OrderCreationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

public abstract class LabCreationListener implements EventListener {

	private static final Logger log = LoggerFactory.getLogger(EncounterCreationListener.class);

	private DaemonToken daemonToken;

	@Autowired
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

	//The two services below are addded so that we can include supportinginfo obs linked in the service request
	@Autowired
	private FhirServiceRequestService fhirServiceRequestService;

	@Autowired
	private FhirObservationService fhirObservationService;

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

	private Bundle createLabBundle(Task task) {
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
		labResources = insertRequisitionIdOnServReq(labResources);
         
		if (!task.getLocation().isEmpty() && config.getLabUpdateTriggerObject().equals("Encounter")) {
			labResources.add(fhirLocationService.get(FhirUtils.referenceToId(task.getLocation().getReference()).get()));
		}
    
		// Add ART Regimen, Pregnancy status, etc. Obs linked on ServiceRequest
        //(1) get task based on -- servicerequest
		//(2) then get supporting info
		//(3) reference to id for each item
		//(4) Pull obs, add to lab bundle
		if(!task.getBasedOn().isEmpty()){
			List <Reference> taskReferences = task.getBasedOn();
			List <String> processedReferences = new ArrayList<String>();
			for(Reference taskReference : taskReferences){
				if(taskReference.getType() == "ServiceRequest"){
					ServiceRequest serviceRequest = fhirServiceRequestService.get(FhirUtils.referenceToId(taskReference.getReference()).get());
					List<Reference> serviceRequestReferences = serviceRequest.getSupportingInfo();
                    for(Reference serviceRequestReference: serviceRequestReferences){
						String ObsId = FhirUtils.referenceToId(serviceRequestReference.getReference()).get();
						if(!ObsId.equals("null") && !processedReferences.contains(ObsId)){ //exclude null (for resources that don't exist) and avoid re-adds to the bundle
							if(serviceRequestReference.getType() == "Observation"){
								labResources.add(fhirObservationService.get(ObsId));
								processedReferences.add(ObsId);
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

	protected void sendTask(Task task) {
		if (task != null) {
			if (config.getActivateFhirPush()) {
				Bundle labBundle = createLabBundle(task);
				client.transaction().withBundle(labBundle).execute();
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
}
