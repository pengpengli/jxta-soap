/*
 * Copyright (c) 2008 JXTA-SOAP Project
 *
 * Redistributions in source code form must reproduce the above copyright
 * and this condition. The contents of this file are subject to the
 * Sun Project JXTA License Version 1.1 (the "License"); you may not use
 * this file except in compliance with the License.
 * A copy of the License is available at http://www.jxta.org/jxta_license.html.
 *
 */


package net.jxta.soap.j2se;

import net.jxta.soap.j2se.deploy.SOAPServiceDeployer;
import net.jxta.soap.j2se.transport.JXTASOAPTransport;

import java.io.InputStream;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;
import javax.xml.namespace.QName;

import net.jxta.discovery.DiscoveryService;
import net.jxta.document.Advertisement;
import net.jxta.document.StructuredDocument;
import net.jxta.impl.document.LiteXMLDocument;
import net.jxta.impl.document.LiteXMLElement;
import net.jxta.peer.PeerID;
import net.jxta.peergroup.PeerGroup;
import net.jxta.pipe.OutputPipe;
import net.jxta.protocol.ModuleSpecAdvertisement;
import net.jxta.protocol.PipeAdvertisement;

import org.apache.axis.client.Call;
import org.apache.axis.client.Service;
import org.apache.log4j.Logger;


public class CallFactory {
    
	private final static Logger LOG = Logger.getLogger(CallFactory.class.getName());
	
    /**
     * Instance member for <code>CallFactory</code>
     */
    private static CallFactory instance = null;
    
    private OutputPipe outputpipe = null;

    private Hashtable<ServiceDescriptor, Service> services = 
		new Hashtable<ServiceDescriptor, Service>();

    /**
     * Create a Call using discovery in the give peergroup to find the
     * PipeAdvertisement of this given ServiceDescriptor.  Given the PeerID, we
     * find the InputPipe for the given service and use that.
     */
    public Call createCall( ServiceDescriptor descriptor,
                            PeerID peerid, 
                            PeerGroup peergroup ) throws Exception {

        //get all modulespecs from the peer we want to communicate with.
        ModuleSpecAdvertisement[] advs = getModuleSpecAdvertisements( descriptor, peerid, peergroup );

        //ok...go though the advertisements and try to bind an output pipe
        OutputPipe outputpipe = null;

        PipeAdvertisement pipeadv = null; 
        for ( int i = 0; i < advs.length; ++i ) {
			try { 
                outputpipe = peergroup.getPipeService()
                    .createOutputPipe( advs[i].getPipeAdvertisement(), descriptor.getTimeout() );
                pipeadv = advs[i].getPipeAdvertisement();                
                break;                
            } catch ( Throwable t ) {
                //acceptable... probably a stale advertisement.          
            }
        } 

        if ( outputpipe == null ) { 
            throw new Exception( "Unable to find a valid output pipe for service" );            
        } 

        //create a call for this and set the output pipe it should use
        //FIXME: AH... infinite loop!
        Call call = createCall( descriptor, pipeadv, peergroup );
        call.setProperty( "outputpipe", outputpipe );

        return call;        
    }

	/**
     * For the given PeerID, find all given services it is running.
     */
    private ModuleSpecAdvertisement[] getModuleSpecAdvertisements( ServiceDescriptor descriptor,
                                                                   PeerID peerid, 
                                                                   PeerGroup peergroup ) throws Exception {

        //FIXME: try to get remote advertisements if they aren't found locally        
        Vector<ModuleSpecAdvertisement> v = new Vector<ModuleSpecAdvertisement>();        
        DiscoveryService ds = peergroup.getDiscoveryService();

        //get all advertisements for this service...
        Enumeration enumeration = ds.getLocalAdvertisements( DiscoveryService.ADV, "Name", descriptor.getName() );        
        
		//go over the advertisements and find the InputPipe adv
        while ( enumeration.hasMoreElements() ) {
 
            Advertisement current = (Advertisement) enumeration.nextElement();
            //ok... if this is a ModuleSpecAdvertisement it might be what I want.
            if ( current instanceof ModuleSpecAdvertisement ) {

                //ok... see if it has the PeerID I need.
                ModuleSpecAdvertisement msa = (ModuleSpecAdvertisement)current;

                //get the peer-id...
                //WARNING: scary casting ahead!
                StructuredDocument sd = msa.getParam();
                if ( sd == null )
                    continue;

                Enumeration penum = ((LiteXMLDocument)sd).getChildren( "peer-id" );
                if ( penum.hasMoreElements() ) {
                    String speerid = (( LiteXMLElement )penum.nextElement() ).getTextValue();
                    if ( peerid.toString().equals( speerid ) ) {
                        v.addElement( msa );                       
                    } 
                } 
                
            } 
            
        }

        ModuleSpecAdvertisement[] result = new ModuleSpecAdvertisement[ v.size() ];
        v.copyInto( result );

        //sort this array based on freshness.
		//Arrays.sort( result, new MSAdvertisementComparator() );
        
        return result;
    }
    

    /**
     * Create a Call object with a pre-existing advertisement.  You will have to
     * do your own discovery to find this one.
     */
    public Call createCall( ServiceDescriptor descriptor,
                            PipeAdvertisement advert,
                            PeerGroup peergroup ) throws Exception {

		Service service = this.getService( descriptor );
		Call call = (Call)service.createCall();
		call = this.processCall( call, descriptor, advert, peergroup );
		return call;
    }


    /**
     * Create a Call object with a pre-existing advertisement.  You will have to
     * do your own discovery to find this one. The service WSDL file must be locally saved, 
     * and the path must be passed as String wsdlLocation
     */
    public Call createCall( ServiceDescriptor descriptor,
							PipeAdvertisement advert,
							PeerGroup peergroup,
							String wsdlLocation,
							QName servicename,
							QName portname ) throws Exception {
		
		if( wsdlLocation == null ) {
			LOG.info("CallFactory.createCall() : wsdlLocation parameter == null!");
		}
		Service service = this.getService( descriptor, wsdlLocation, servicename );
		
		Call call = (Call)service.createCall( portname );
		call = this.processCall( call, descriptor, advert, peergroup );
		return call;
    }
    
    /**
     * Create a Call object with a pre-existing advertisement. You will have to
     * do your own discovery to find this one. The service WSDL file must be passed 
     * as InputStream
     */    
    public Call createCall( ServiceDescriptor descriptor,
			PipeAdvertisement advert,
			PeerGroup peergroup,
			InputStream wsdlInputStream,
			QName servicename,
			QName portname ) throws Exception {

    	Service service = this.getService( descriptor, wsdlInputStream, servicename );

    	Call call = (Call)service.createCall( portname );
    	call = this.processCall( call, descriptor, advert, peergroup );
    	return call;
    }


    /**
     * Create a Call object with a pre-existing advertisement.  You will have to
     * do your own discovery to find this one. The service WSDL file must be locally saved, 
     * and the path must be passed as String wsdlLocation
     */
    public Call createCall( ServiceDescriptor descriptor,
							PipeAdvertisement advert,
							PeerGroup peergroup,
							String wsdlLocation,
							QName servicename,
							QName portname,
							QName operationName ) throws Exception {

		Service service = this.getService( descriptor, wsdlLocation, servicename );
		Call call = (Call)service.createCall( portname, operationName );
		call = this.processCall( call, descriptor, advert, peergroup );
		return call;
    }
    
    /**
     * Create a Call object with a pre-existing advertisement.  You will have to
     * do your own discovery to find this one. The service WSDL file must be passed 
     * as InputStream
     */
    public Call createCall( ServiceDescriptor descriptor,
							PipeAdvertisement advert,
							PeerGroup peergroup,
							InputStream wsdlInputStream,
							QName servicename,
							QName portname,
							QName operationName ) throws Exception {

		Service service = this.getService( descriptor, wsdlInputStream, servicename );
		Call call = (Call)service.createCall( portname, operationName );
		call = this.processCall( call, descriptor, advert, peergroup );
		return call;
    }
    
    
    /**
     * Helper method for the createCall() methods
     */     
    private Service getService( ServiceDescriptor descriptor ) throws Exception {
		//get the service
		Service service = services.get( descriptor );
		
		if ( service == null ) {
			service = new SOAPServiceDeployer( descriptor ).getService();
			services.put( descriptor, service );
		}	    
		return service;
    }

    /** 
     * Helper method for the createCall() methods
     */      
    private Service getService( ServiceDescriptor descriptor,
								InputStream wsdlInputStream,
								QName servicename) throws Exception {
		//get the service
		Service service = services.get( descriptor );
		
		if ( service == null ) {
			LOG.info("Trying to get service with pre-filled wsdl data");
		
			// debugging stuff
			if (wsdlInputStream == null){
				LOG.info("wsdlInputStream argument is empty!");
			}
			
			service = new SOAPServiceDeployer( descriptor ).getService( wsdlInputStream, servicename );
			services.put( descriptor, service );
		}
		return service;
    }

    /** 
     * Helper method for the createCall() methods
     */      
    private Service getService( ServiceDescriptor descriptor,
								String wsdlLocation,
								QName servicename) throws Exception {
		//get the service
		Service service = services.get( descriptor );
		
		if ( service == null ) {
			LOG.info("Trying to get service with pre-filled wsdl data");
		
			// debugging stuff
			if (wsdlLocation == null){
				LOG.info("wsdlLocation argument is empty!");
			}
			
			service = new SOAPServiceDeployer( descriptor ).getService( wsdlLocation, servicename );
			services.put( descriptor, service );
		}
		return service;
    }


    /**
     * Helper method to set the JXTA-SOAP properties of the Call object
     */     
    private Call processCall( Call call,
							ServiceDescriptor descriptor,
							PipeAdvertisement advert,
							PeerGroup peergroup ) {
		try {
			call.setTransport( new JXTASOAPTransport() );
			
			//we need to pass peergroup, advertisement, etc as a property because
			//these are needed by the transport.
			
			call.setProperty( "peergroup", peergroup );
			call.setProperty( "advertisement", advert );
			call.setProperty( "descriptor", descriptor );
			
			call.getMessageContext().setTargetService( descriptor.getName() );
			
			return call;
		} catch (Exception e){
			LOG.info("Error in CallFactory.processCall()");
			e.printStackTrace();
			System.exit(1);
		}
		return null;
    }
	
    
    /**
     * Get an instance of the <code>CallFactory</code>.
     */
    public static CallFactory getInstance() {
        
        if ( instance == null ) {
            
            instance = new CallFactory();
            
        }
        
        return instance;
        
    }

}


class MSAdvertisementComparator implements Comparator {
    public int compare( Object one, Object two ) throws ClassCastException {
        ModuleSpecAdvertisement msa1 = (ModuleSpecAdvertisement)one;
        ModuleSpecAdvertisement msa2 = (ModuleSpecAdvertisement)two;
	
        // ModuleSpecAdvertisements don't support the getLocalExpirationTime()
        // method anymore. Return 0 for equal time.
        return 0;
    }
    
    public boolean equals( Object obj ) {
        return obj.equals( this );
    }
    
}
