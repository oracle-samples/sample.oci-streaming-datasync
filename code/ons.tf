# Copyright (c)  2022,  Oracle and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.



resource oci_ons_notification_topic export_ErrorTopic {
  compartment_id = var.compartment_ocid
 
  description = "Topic to send notifications during unrecoverable error"
  freeform_tags = {
  }
  name = "ErrorTopic"
}

resource oci_ons_subscription export_subscription_1 {
  compartment_id = var.compartment_ocid
  
  delivery_policy = "{\"backoffRetryPolicy\":{\"maxRetryDuration\":7200000,\"policyType\":\"EXPONENTIAL\"}}"
  endpoint        = var.notification_email_id
  freeform_tags = {
  }
  protocol = "EMAIL"
  topic_id = oci_ons_notification_topic.export_ErrorTopic.id
}

