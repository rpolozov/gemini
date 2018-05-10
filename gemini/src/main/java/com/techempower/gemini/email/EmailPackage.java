/*******************************************************************************
 * Copyright (c) 2018, TechEmpower, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name TechEmpower, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived from
 *       this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL TECHEMPOWER, INC. BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *******************************************************************************/

package com.techempower.gemini.email;

import java.util.*;

import com.techempower.gemini.*;
import com.techempower.helper.*;

/**
 * Contains information about whom to email and what to e-mail them.  Used
 * by many of the Gemini e-mail management components.  For instance, the
 * EmailTemplater will generate EmailPackages based off of templates.  Custom
 * e-mails can also be generated by just calling the EmailPackage constructor
 * directly.
 *    <p>
 * Example use:
 *    <p>
 * <pre><br>
 *   Hashtable macros = new Hashtable();
 *   macros.put("$U", username);
 *   macros.put("$F", fullname);
 *
 *   EmailTemplater templater   = application.getEmailTemplater();
 *   EmailServicer  servicer    = application.getEmailServicer();
 *   EmailPackage   welcomeMail = templater.getEmail(
 *     "E-WelcomeMail",               // String name of the template to use
 *     macros,                        // Macros hashtable to expand template
 *     outgoingMailAuthor,            // The author's e-mail address.
 *     userEmailAddress);             // Recipient's e-mail address.
 *   servicer.sendMail(welcomeMail);  // Does not block.
 * </pre>
 */
public class EmailPackage
{
  //
  // Static members.
  //

  public  static final String JAVAX_MAIL_MESSAGE = "javax.mail.Message";
  //private static final long   serialVersionUID   = 371890138153L;
  
  //
  // Member variables.
  //
  
  // -->                                                  "*" indicates data that is Externalized.
  private String                     recipientAddress      = "";      // * Empty recipient by default.
  private String                     recipientSource       = "";      //   Empty source by default.
  private String                     mailServer            = null;    //   Mail server to use when delivering mail
  private String                     messageBody           = "";      // * Empty plain text message by default.
  private String                     htmlMessageBody       = "";      // * Empty HTML message by default.
  private String                     authorAddress         = "";      // * Empty author by default.
  private String                     bccRecipientAddress   = "";      // * Empty bcc recipient by default.
  private String                     subject               = "";      // * Empty subject by default.
  private String                     charset               = "";      // * Empty charset by default.
  private List<EmailAttachment>      attachments           = null;    //   Optional EmailAttachments
  private Map<String,Object>         custom                = null;    //   Optional custom attributes
  private Collection<EmailHeader>    headers               = null;    //   Optional header attribute for the email
  private boolean                    sent                  = false;   //   Optionally set by Transport.
  private boolean                    successful            = false;   // * Optionally set by Transport.
  private boolean                    hasHtmlBody           = false;   // * Email has an HTML body.
  private boolean                    hasTextBody           = false;   // * Email has a text body.
  private EmailAuthenticator         authenticator         = null;    //   Authenticator to use when delivering mail.
  
  private int                        deliveryAttempts      = 0;       // * Number of times EmailTransport has tried to deliver.
  //private int                        totalDeliveryAttempts = 0;       // * A counter that is not reset.

  //
  // Member methods.
  //

  /**
   * Constructor.  Email Package without a file attachment but with both an 
   * html body and plain text body.
   */
  public EmailPackage(String subject, String plainBody, String htmlBody, 
    String recipient, String author)
  {
    setTextBody(plainBody);
    setHtmlBody(htmlBody);
    setSubject(subject);
    setRecipient(recipient);
    setAuthor(author);
    setHtmlEnabled(true);
  }
  
  /**
   * Standard constructor.  Email Package without a file attachment.
   */
  public EmailPackage(String subject, String messageBody, String recipient,
    String author)
  {
    setTextBody(messageBody);
    setSubject(subject);
    setRecipient(recipient);
    setAuthor(author);
  }

  /**
   * Smaller constructor.  Assumes no recipient or author is yet set.
   */
  public EmailPackage(String subject, String messageBody)
  {
    setTextBody(messageBody);
    setSubject(subject);
  }

  /**
   * Smaller constructor.  Assumes no author, subject, or body is yet set.
   */
  public EmailPackage(String recipient)
  {
    setRecipient(recipient);
  }

  /**
   * Reads this object from an ObjectStream.
   */
  /*
  @Override
  public void readExternal(ObjectInput input)
    throws    IOException
  {
    this.authorAddress         = input.readUTF();
    this.recipientAddress      = input.readUTF();
    this.subject               = input.readUTF();
    input.readInt();   // Ignored herein.
    this.messageBody           = input.readUTF();
    this.successful            = input.readBoolean();
    this.hasHtmlBody           = input.readBoolean();
    this.deliveryAttempts      = input.readInt();
    this.totalDeliveryAttempts = input.readInt();
  }
  */

  /**
   * Writes this object to an ObjectStream.
   */
  /*
  @Override
  public void writeExternal(ObjectOutput output)
    throws    IOException
  {
    output.writeUTF(this.authorAddress);
    output.writeUTF(this.recipientAddress);
    output.writeUTF(this.subject);
    output.writeInt(this.attachments.size());         // Write just the number of attachments
    output.writeUTF(this.messageBody);
    output.writeBoolean(this.successful);
    output.writeBoolean(this.hasHtmlBody);
    output.writeInt(this.deliveryAttempts);
    output.writeInt(this.totalDeliveryAttempts);
  }
  */

  /**
   * Sets a custom attribute, for instance, username.  Custom attributes do 
   * not affect anything in the default implementations of the Gemini
   * emailer components, but could be used to send out special e-mails in
   * custom implementations of EmailServicer, for instance.
   */
  public void setCustomAttribute(String customAttributeName, Object value)
  {
    if (this.custom == null)
    {
      this.custom = new HashMap<>();
    }
    this.custom.put(customAttributeName, value);
  }

  /**
   * Gets a custom attribute.  If no such attribute, returns empty 
   * string.
   */
  public String getCustomAttribute(String customAttributeName)
  {
    if (this.custom != null)
    {
      String value = (String)this.custom.get(customAttributeName);
      if (value != null)
      {
        return value;
      }
    }

    return "";
  }

  /**
   * Get the custom attribute as an object.
   */
  public Object getCustomAttributeObject(String customAttributeName)
  {
    if (this.custom != null)
    {
      return this.custom.get(customAttributeName);
    }
    else
    {
      return null;
    }
  }
  
  /**
   * Gets the headers for this email
   */
  public Collection<EmailHeader> getHeaders()
  {
    return this.headers;
  }
  
  /**
   * Gets an individual header
   */
  public EmailHeader getHeader(String headerName)
  {
    if (this.headers != null)
    {
      for (EmailHeader emailHeader : this.headers)
      {
        if (emailHeader.getHeaderName().equals(headerName))
        {
          return emailHeader;
        }
      }
    }
    return null;
  }
  
  /**
   * Sets the header collection
   */
  public void setHeaders(Collection<EmailHeader> headers)
  {
    this.headers = headers;
  }
  
  /**
   * Adds a header
   */
  public void addHeader(EmailHeader header)
  {
    if (this.headers == null)
    {
      this.headers = new ArrayList<>();
    }
    this.headers.add(header);
  }
  
  /**
   * Removes a header
   */
  public void removeHeader(String headerName)
  {
    if (this.headers != null)
    {
      for (Iterator<EmailHeader> it = this.headers.iterator(); it.hasNext();)
      {
        EmailHeader header = it.next();
        if (header.getHeaderName().equals(headerName))
        {
          it.remove(); 
        }
      }
    }
  }

  /**
   * Sets the sent flag.
   */
  public void setSent(boolean sent)
  {
    this.sent = sent;
  }

  /**
   * Gets the sent flag.
   */
  public boolean wasSent()
  {
    return this.sent;
  }

  /**
   * Sets the successful flag.
   */
  public void setSuccessful(boolean success)
  {
    this.successful = success;
  }

  /**
   * Gets the successful flag.
   */
  public boolean wasSuccessful()
  {
    return this.successful;
  }

  /**
   * Increments the number of delivery attempts.  Generally, this method is
   * only called if a delivery attempt fails.  That is, if an e-mail is
   * successfully sent on the first delivery attempt, the number of
   * delivery attempts returned by getDeliveryAttempts will be 0.
   */
  public void incrementDeliveryAttempts()
  {
    this.deliveryAttempts++;
    //this.totalDeliveryAttempts++;
  }

  /**
   * Resets the delivery attempts to 0.  This is done when queuing
   * a new e-mail into an EmailServicer.
   */
  public void resetDeliveryAttempts()
  {
    this.deliveryAttempts = 0;
  }

  /**
   * Gets the delivery attempts count for this e-mail.
   */
  public int getDeliveryAttempts()
  {
    return this.deliveryAttempts;
  }

  /**
   * Sets the recipient.
   */
  public void setRecipient(String recipientAddress)
  {
    this.recipientAddress = recipientAddress;
  }

  /**
   * Gets the recipient source.
   */
  public String getRecipientSource()
  {
    return this.recipientSource;
  }

  /**
   * Sets the recipient source.
   */
  public void setRecipientSource(String recipientSource)
  {
    this.recipientSource = recipientSource;
  }

  /**
   * Gets the recipient.
   */
  public String getRecipient()
  {
    return this.recipientAddress;
  }

  /**
   * Sets the author.
   */
  public void setAuthor(String authorAddress)
  {
    this.authorAddress = authorAddress;
  }

  /**
   * Gets the author.
   */
  public String getAuthor()
  {
    return this.authorAddress;
  }

  /**
   * Sets the BCC Recipient.
   */
  public void setBccRecipient(String bccRecipientAddress)
  {
    this.bccRecipientAddress = bccRecipientAddress;
  }

  /**
   * Gets the BCC Recipient.
   */
  public String getBccRecipient()
  {
    return this.bccRecipientAddress;
  }

  /**
   * Sets the subject.
   */
  public void setSubject(String subject)
  {
    this.subject = subject;
  }

  /**
   * Gets the charset.
   */
  public String getCharset()
  {
    return this.charset;
  }
  
  /**
   * Sets the charset.
   */
  public void setCharset(String charset)
  {
    this.charset = charset;
  }

  /**
   * Gets the subject.
   */
  public String getSubject()
  {
    return this.subject;
  }

  /**
   * Does this EmailPackage have attachments?
   */
  public boolean hasAttachments()
  {
    return ( (this.attachments != null)
      &&     (!this.attachments.isEmpty()) );
  }

  /**
   * Sets the plain text body part of the email.
   */
  public void setTextBody(String messageBody)
  {
    this.messageBody = messageBody;
    setTextEnabled(messageBody != null);
  }

  /**
   * Gets the plain text message body part.
   */
  public String getTextBody()
  {
    return this.messageBody;
  }
  
  /**
   * Sets the html body part of the email.  Sets the HTML enabled flag
   * to true if the parameter is non-null.
   */
  public void setHtmlBody(String messageBody)
  {
    this.htmlMessageBody = messageBody;
    setHtmlEnabled(messageBody != null);
  }

  /**
   * Gets the html message body part.
   */
  public String getHtmlBody()
  {
    String tempTextBody = GeminiHelper.convertHtmlToText(
        this.htmlMessageBody).trim();
    if (tempTextBody.length() == 0)
    {
      return getTextBody();
    }
    else
    {
      return this.htmlMessageBody;
    }
  }

  /**
   * Adds an EmailAttachment to the EmailPackage.
   */
  public void addAttachment(EmailAttachment attachment)
  {
    if (attachment != null)
    {
      if (this.attachments == null)
      {
        this.attachments = new ArrayList<>();
      }
      this.attachments.add(attachment);
    }
  }
  
  /**
   * Sets a collection of EmailAttachments to the EmailPackage.
   */
  public void setAttachments(Collection<EmailAttachment> pAttachments)
  {
    if (this.attachments != null)
    {
      for (EmailAttachment pAttachment : pAttachments)
      {
        this.attachments.add(pAttachment);
      }
    }
    else
    {
      this.attachments = new ArrayList<>(pAttachments);
    }
  }
  
  /**
   * Returns a collection of EmailAttachments.
   */
  public Collection<EmailAttachment> getAttachments()
  {
    return this.attachments;
  }

  /**
   * Sets the mail server.
   */
  public void setMailServer(String mailServer)
  {
    this.mailServer = mailServer;
  }

  /**
   * Gets the mail server.
   */
  public String getMailServer()
  {
    return this.mailServer;
  }
  
  /**
   * Gets the email authenticator.
   */
  public EmailAuthenticator getEmailAuthenticator()
  {
    return this.authenticator;
  }
  
  /**
   * Sets the email authenticator.
   */
  public void setEmailAuthenticator(EmailAuthenticator authenticator)
  {
    this.authenticator = authenticator;
  }
  
  /**
   * Sets the EmailPackage as having an html body part.
   */
  public void setHtmlEnabled(boolean htmlFlag)
  {
    this.hasHtmlBody = htmlFlag;
  }
  
  /**
   * Gets whether the email has an html body part.
   */
  public boolean isHtmlEnabled()
  {
    return this.hasHtmlBody;
  }

  /**
   * Sets the EmailPackage as having a text body part.
   */
  public void setTextEnabled(boolean htmlFlag)
  {
    this.hasTextBody = htmlFlag;
  }
  
  /**
   * Gets whether the email has a text body part.
   */
  public boolean isTextEnabled()
  {
    return this.hasTextBody;
  }

  /**
   * Standard Java toString.
   */
  @Override
  public String toString()
  {
    return "EmailPackage [f:" + getAuthor() 
      + "; t:" + getRecipient() 
      + "; s:" + StringHelper.truncateEllipsis(getSubject(), 20) 
      + "]";
  }

}   // End EmailPackage.
