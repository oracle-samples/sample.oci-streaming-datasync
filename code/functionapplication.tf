# Copyright (c)  2022,  Oracle and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.



resource oci_functions_application export_DataSyncApplication {
  compartment_id = var.compartment_ocid
  config = {
    "data_stream_ocid"    = oci_streaming_stream.export_DataSyncStream.id
    "unrecoverable_error_stream_ocid"    = oci_streaming_stream.export_UnrecoverableErrorStream.id
    "serviceUnavailable_error_stream_ocid"    = oci_streaming_stream.export_ServerUnavailableStream.id
    "default_error_stream_ocid" = oci_streaming_stream.export_ServerUnavailableStream.id
    "vault_compartment_ocid"    = var.stream_compartment_ocid
    "stream_compartment_ocid"   = var.vault_compartment_ocid
    "vault_key_ocid"            = oci_kms_key_version.export_SyncDataEncryptionKey_key_version_1.id
    "vault_ocid"                = oci_kms_vault.export_DataSync_Vault.id
    "internalserver_error_stream_ocid" =oci_streaming_stream.export_InternalserverErrorStream.id
  }
 
  display_name = "DataSyncApplication"
  freeform_tags = {
  }
  network_security_group_ids = [
  ]
  subnet_ids = [
    oci_core_subnet.export_Public-Subnet-DataSyncVCN.id,
  ]
  syslog_url = ""
  trace_config {
    domain_id  = ""
    is_enabled = "false"
  }
}





