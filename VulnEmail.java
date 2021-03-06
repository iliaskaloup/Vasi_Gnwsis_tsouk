/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.actions.email.service;

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.base.Charsets;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;

import javax.mail.MessagingException;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;

import static org.elasticsearch.common.joda.time.DateTimeZone.UTC;
/**
 *
 */
public class Email implements ToXContent {

    final String id;
    final Address from;
    final AddressList replyTo;
    final Priority priority;
    final DateTime sentDate;
    final AddressList to;
    final AddressList cc;
    final AddressList bcc;
    final String subject;
    final String textBody;
    final String htmlBody;
    final ImmutableMap<String, Attachment> attachments;
    final ImmutableMap<String, Inline> inlines;

    public Email(String id, Address from, AddressList replyTo, Priority priority, DateTime sentDate,
                 AddressList to, AddressList cc, AddressList bcc, String subject, String textBody, String htmlBody,
                 ImmutableMap<String, Attachment> attachments, ImmutableMap<String, Inline> inlines) {

        this.id = id;
        this.from = from;
        this.replyTo = replyTo;
        this.priority = priority;
        this.sentDate = sentDate != null ? sentDate : new DateTime(UTC);
        this.to = to;
        this.cc = cc;
        this.bcc = bcc;
        this.subject = subject;
        this.textBody = textBody;
        this.htmlBody = htmlBody;
        this.attachments = attachments;
        this.inlines = inlines;
    }

    public String id() {
        return id;
    }

    public Address from() {
        return from;
    }

    public AddressList replyTo() {
        return replyTo;
    }

    public Priority priority() {
        return priority;
    }

    public DateTime sentDate() {
        return sentDate;
    }

    public AddressList to() {
        return to;
    }

    public AddressList cc() {
        return cc;
    }

    public AddressList bcc() {
        return bcc;
    }

    public String subject() {
        return subject;
    }

    public String textBody() {
        return textBody;
    }

    public String htmlBody() {
        return htmlBody;
    }

    public ImmutableMap<String, Attachment> attachments() {
        return attachments;
    }

    public ImmutableMap<String, Inline> inlines() {
        return inlines;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(Field.ID.getPreferredName(), id);
        if (from != null) {
            builder.field(Field.FROM.getPreferredName(), from, params);
        }
        if (replyTo != null) {
            builder.field(Field.REPLY_TO.getPreferredName(), replyTo, params);
        }
        if (priority != null) {
            builder.field(Field.PRIORITY.getPreferredName(), priority, params);
        }
        builder.field(Field.SENT_DATE.getPreferredName(), sentDate);
        if (to != null) {
            builder.field(Field.TO.getPreferredName(), to, params);
        }
        if (cc != null) {
            builder.field(Field.CC.getPreferredName(), cc, params);
        }
        if (bcc != null) {
            builder.field(Field.BCC.getPreferredName(), bcc, params);
        }
        builder.field(Field.SUBJECT.getPreferredName(), subject);
        builder.field(Field.TEXT_BODY.getPreferredName(), textBody);
        if (htmlBody != null) {
            builder.field(Field.HTML_BODY.getPreferredName(), htmlBody);
        }
        return builder.endObject();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Email email = (Email) o;

        if (!id.equals(email.id)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Email parse(XContentParser parser) throws IOException{
        Builder email = new Builder();
        String currentFieldName = null;
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if ((token.isValue() || token == XContentParser.Token.START_OBJECT || token == XContentParser.Token.START_ARRAY) && currentFieldName != null) {
                if (Field.ID.match(currentFieldName)) {
                    email.id(parser.text());
                } else if (Field.FROM.match(currentFieldName)) {
                    email.from(Address.parse(currentFieldName, token, parser));
                } else if (Field.REPLY_TO.match(currentFieldName)) {
                    email.replyTo(AddressList.parse(currentFieldName, token, parser));
                } else if (Field.TO.match(currentFieldName)) {
                    email.to(AddressList.parse(currentFieldName, token, parser));
                } else if (Field.CC.match(currentFieldName)) {
                    email.cc(AddressList.parse(currentFieldName, token, parser));
                } else if (Field.BCC.match(currentFieldName)) {
                    email.bcc(AddressList.parse(currentFieldName, token, parser));
                } else if (Field.PRIORITY.match(currentFieldName)) {
                    email.priority(Email.Priority.resolve(parser.text()));
                } else if (Field.SENT_DATE.match(currentFieldName)) {
                    email.sentDate(new DateTime(parser.text()));
                } else if (Field.SUBJECT.match(currentFieldName)) {
                    email.subject(parser.text());
                } else if (Field.TEXT_BODY.match(currentFieldName)) {
                    email.textBody(parser.text());
                } else if (Field.HTML_BODY.match(currentFieldName)) {
                    email.htmlBody(parser.text());
                } else {
                    throw new ParseException("could not parse email. unrecognized field [" + currentFieldName + "]");
                }
            }
        }
        return email.build();
    }

    public static class Builder {

        private String id;
        private Address from;
        private AddressList replyTo;
        private Priority priority;
        private DateTime sentDate;
        private AddressList to;
        private AddressList cc;
        private AddressList bcc;
        private String subject;
        private String textBody;
        private String htmlBody;
        private ImmutableMap.Builder<String, Attachment> attachments = ImmutableMap.builder();
        private ImmutableMap.Builder<String, Inline> inlines = ImmutableMap.builder();

        private Builder() {
        }

        public Builder copyFrom(Email email) {
            id = email.id;
            from = email.from;
            replyTo = email.replyTo;
            priority = email.priority;
            sentDate = email.sentDate;
            to = email.to;
            cc = email.cc;
            bcc = email.bcc;
            subject = email.subject;
            textBody = email.textBody;
            htmlBody = email.htmlBody;
            attachments.putAll(email.attachments);
            inlines.putAll(email.inlines);
            return this;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder from(String address) throws AddressException {
            return from(new Address(address));
        }

        public Builder from(Address from) {
            this.from = from;
            return this;
        }

        public Builder replyTo(AddressList replyTo) {
            this.replyTo = replyTo;
            return this;
        }

        public Builder replyTo(String addresses) throws AddressException {
            return replyTo(Email.AddressList.parse(addresses));
        }

        public Builder priority(Priority priority) {
            this.priority = priority;
            return this;
        }

        public Builder sentDate(DateTime sentDate) {
            this.sentDate = sentDate;
            return this;
        }

        public Builder to(String addresses) throws AddressException {
            return to(AddressList.parse(addresses));
        }

        public Builder to(AddressList to) {
            this.to = to;
            return this;
        }

        public AddressList to() {
            return to;
        }

        public Builder cc(String addresses) throws AddressException {
            return cc(AddressList.parse(addresses));
        }

        public Builder cc(AddressList cc) {
            this.cc = cc;
            return this;
        }

        public Builder bcc(String addresses) throws AddressException {
            return bcc(AddressList.parse(addresses));
        }

        public Builder bcc(AddressList bcc) {
            this.bcc = bcc;
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder textBody(String text) {
            this.textBody = text;
            return this;
        }

        public Builder htmlBody(String html) {
            this.htmlBody = html;
            return this;
        }

        public Builder attach(Attachment attachment) {
            attachments.put(attachment.id(), attachment);
            return this;
        }

        public Builder inline(Inline inline) {
            inlines.put(inline.id(), inline);
            return this;
        }

        public Email build() {
            assert id != null : "email id should not be null (should be set to the watch id";
            return new Email(id, from, replyTo, priority, sentDate, to, cc, bcc, subject, textBody, htmlBody, attachments.build(), inlines.build());
        }

    }

    public enum Priority implements ToXContent {

        HIGHEST(1),
        HIGH(2),
        NORMAL(3),
        LOW(4),
        LOWEST(5);

        static final String HEADER = "X-Priority";

        private final int value;

        Priority(int value) {
            this.value = value;
        }

        public void applyTo(MimeMessage message) throws MessagingException {
            message.setHeader(HEADER, String.valueOf(value));
        }


        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return builder.value(name().toLowerCase(Locale.ROOT));
        }

        public static Priority resolve(String name) {
            Priority priority = resolve(name, null);
            if (priority == null) {
                throw new EmailSettingsException("unknown email priority [" + name + "]");
            }
            return priority;
        }

        public static Priority resolve(String name, Priority defaultPriority) {
            if (name == null) {
                return defaultPriority;
            }
            switch (name.toLowerCase(Locale.ROOT)) {
                case "highest": return HIGHEST;
                case "high":    return HIGH;
                case "normal":  return NORMAL;
                case "low":     return LOW;
                case "lowest":  return LOWEST;
                default:
                    return defaultPriority;
            }
        }

        public static Priority parse(Settings settings, String name) {
            String value = settings.get(name);
            if (value == null) {
                return null;
            }
            return resolve(value);
        }
    }

    public static class Address extends javax.mail.internet.InternetAddress implements ToXContent {

        public static final ParseField ADDRESS_NAME_FIELD = new ParseField("name");
        public static final ParseField ADDRESS_EMAIL_FIELD = new ParseField("email");

        public Address(String address) throws AddressException {
            super(address);
        }

        public Address(String address, String personal) throws UnsupportedEncodingException {
            super(address, personal, Charsets.UTF_8.name());
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return builder.value(toUnicodeString());
        }

        public static Address parse(String field, XContentParser.Token token, XContentParser parser) throws IOException {
            if (token == XContentParser.Token.VALUE_STRING) {
                String text = parser.text();
                try {
                    return new Email.Address(parser.text());
                } catch (AddressException ae) {
                    throw new ParseException("could not parse [" + text + "] in field [" + field + "] as address. address must be RFC822 encoded", ae);
                }
            }

            if (token == XContentParser.Token.START_OBJECT) {
                String email = null;
                String name = null;
                String currentFieldName = null;
                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                    if (token == XContentParser.Token.FIELD_NAME) {
                        currentFieldName = parser.currentName();
                    } else if (token == XContentParser.Token.VALUE_STRING) {
                        if (ADDRESS_EMAIL_FIELD.match(currentFieldName)) {
                            email = parser.text();
                        } else if (ADDRESS_NAME_FIELD.match(currentFieldName)) {
                            name = parser.text();
                        } else {
                            throw new ParseException("could not parse [" + field + "] object as address. unknown address field [" + currentFieldName + "]");
                        }
                    }
                }
                if (email == null) {
                    throw new ParseException("could not parse [" + field + "] as address. address object must define an [email] field");
                }
                try {
                    return name != null ? new Email.Address(email, name) : new Email.Address(email);
                } catch (AddressException ae) {
                    throw new ParseException("could not parse [" + field + "] as address", ae);
                }

            }
            throw new ParseException("could not parse [" + field + "] as address. address must either be a string (RFC822 encoded) or an object specifying the address [name] and [email]");
        }

        public static Address parse(Settings settings, String name) {
            String value = settings.get(name);
            try {
                return value != null ? new Address(value) : null;
            } catch (AddressException ae) {
                throw new EmailSettingsException("could not parse [" + value + "] as a RFC822 email address", ae);
            }
        }
    }

    public static class AddressList implements Iterable<Address>, ToXContent {

        public static final AddressList EMPTY = new AddressList(Collections.<Address>emptyList());

        private final List<Address> addresses;

        public AddressList(List<Address> addresses) {
            this.addresses = addresses;
        }

        public boolean isEmpty() {
            return addresses.isEmpty();
        }

        @Override
        public Iterator<Address> iterator() {
            return addresses.iterator();
        }

        public Address[] toArray() {
            return addresses.toArray(new Address[addresses.size()]);
        }

        public int size() {
            return addresses.size();
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startArray();
            for (Address address : addresses) {
                address.toXContent(builder, params);
            }
            return builder.endArray();
        }

        public static AddressList parse(String text) throws AddressException {
            InternetAddress[] addresses = InternetAddress.parse(text);
            List<Address> list = new ArrayList<>(addresses.length);
            for (InternetAddress address : addresses) {
                list.add(new Address(address.toUnicodeString()));
            }
            return new AddressList(list);
        }

        public static AddressList parse(Settings settings, String name) {
            String[] addresses = settings.getAsArray(name);
            if (addresses == null || addresses.length == 0) {
                return null;
            }
            try {
                List<Address> list = new ArrayList<>(addresses.length);
                for (String address : addresses) {
                    list.add(new Address(address));
                }
                return new AddressList(list);
            } catch (AddressException ae) {
                throw new EmailSettingsException("could not parse [" + settings.get(name) + "] as a list of RFC822 email address", ae);
            }
        }

        public static Email.AddressList parse(String field, XContentParser.Token token, XContentParser parser) throws IOException {
            if (token == XContentParser.Token.VALUE_STRING) {
                String text = parser.text();
                try {
                    return parse(parser.text());
                } catch (AddressException ae) {
                    throw new ParseException("could not parse field [" + field + "] with value [" + text + "] as address list. address(es) must be RFC822 encoded", ae);
                }
            }
            if (token == XContentParser.Token.START_ARRAY) {
                List<Email.Address> addresses = new ArrayList<>();
                while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                    addresses.add(Address.parse(field, token, parser));
                }
                return new Email.AddressList(addresses);
            }
            throw new ParseException("could not parse [" + field + "] as address list. field must either be a string " +
                    "(comma-separated list of RFC822 encoded addresses) or an array of objects representing addresses");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            AddressList addresses1 = (AddressList) o;

            if (!addresses.equals(addresses1.addresses)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            return addresses.hashCode();
        }
    }

    public static class ParseException extends EmailException {

        public ParseException(String msg) {
            super(msg);
        }

        public ParseException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    interface Field {
        ParseField ID = new ParseField("id");
        ParseField FROM = new ParseField("from");
        ParseField REPLY_TO = new ParseField("reply_to");
        ParseField PRIORITY = new ParseField("priority");
        ParseField SENT_DATE = new ParseField("sent_date");
        ParseField TO = new ParseField("to");
        ParseField CC = new ParseField("cc");
        ParseField BCC = new ParseField("bcc");
        ParseField SUBJECT = new ParseField("subject");
        ParseField TEXT_BODY = new ParseField("text_body");
        ParseField HTML_BODY = new ParseField("html_body");
    }

}