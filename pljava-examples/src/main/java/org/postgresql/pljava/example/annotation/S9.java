/*
 * Copyright (c) 2018- Tada AB and other contributors, as listed below.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the The BSD 3-Clause License
 * which accompanies this distribution, and is available at
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Contributors:
 *   Chapman Flack
 */
package org.postgresql.pljava.example.annotation;

import java.io.StringReader;

import java.math.BigDecimal;
import java.math.BigInteger;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import static java.sql.ResultSetMetaData.columnNoNulls;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;

import java.sql.SQLException;
import java.sql.SQLDataException;
import java.sql.SQLNonTransientException;
import java.sql.SQLSyntaxErrorException;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.om.SequenceIterator;

import net.sf.saxon.query.QueryResult;
import net.sf.saxon.query.StaticQueryContext;

import net.sf.saxon.tree.iter.LookaheadIterator;

import net.sf.saxon.s9api.DocumentBuilder;
import net.sf.saxon.s9api.ItemType;
import net.sf.saxon.s9api.ItemTypeFactory;
import net.sf.saxon.s9api.OccurrenceIndicator;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SequenceType;
import net.sf.saxon.s9api.XdmAtomicValue;
import static net.sf.saxon.s9api.XdmAtomicValue.makeAtomicValue;
import net.sf.saxon.s9api.XdmEmptySequence;
import static net.sf.saxon.s9api.XdmNodeKind.DOCUMENT;
import net.sf.saxon.s9api.XdmValue;
import net.sf.saxon.s9api.XQueryCompiler;
import net.sf.saxon.s9api.XQueryEvaluator;
import net.sf.saxon.s9api.XQueryExecutable;

import net.sf.saxon.s9api.SaxonApiException;

import net.sf.saxon.trans.XPathException;

import net.sf.saxon.value.Base64BinaryValue;
import net.sf.saxon.value.HexBinaryValue;

import org.postgresql.pljava.annotation.Function;
import static org.postgresql.pljava.annotation.Function.OnNullInput.CALLED;

/**
 * Class illustrating use of XQuery with Saxon as the
 * implementation, using its native "s9api".
 *<p>
 * Supplies alternative, XML Query-based (as the SQL/XML standard dictates)
 * implementation of some of SQL/XML, where the implementation in core
 * PostgreSQL is limited to the capabilities of XPath (and XPath 1.0, at that).
 *<p>
 * Without the syntatic sugar built into the core PostgreSQL parser, calls to
 * a function in this class can look a bit more verbose in SQL, but reflect a
 * straightforward rewriting from the standard syntax. For example, suppose
 * there is a table {@code catalog_as_xml} with a single row whose {@code x}
 * column is a (respectably sized) XML document recording the stuff in
 * {@code pg_catalog}. It could be created like this:
 *<pre>
 * CREATE TABLE catalog_as_xml(x) AS
 *   SELECT schema_to_xml('pg_catalog', false, true, '');
 *</pre>
 *<p>
 * In the syntax of the SQL/XML standard, here is a query that would return
 * an XML element representing the declaration of a function with a specified
 * name:
 *<pre>
 * SELECT XMLQUERY('/pg_catalog/pg_proc[proname eq $NAME]'
 *                 PASSING BY VALUE x, 'numeric_avg' AS NAME
 *                 RETURNING CONTENT EMPTY ON EMPTY)
 * FROM catalog_as_xml;
 *</pre>
 *<p>
 * It binds the 'context item' of the query to {@code x}, and the {@code NAME}
 * parameter to the given value, then evaluates the query and returns XML
 * "CONTENT" (a tree structure with a document node at the root, but not
 * necessarily meeting all the requirements of an XML "DOCUMENT"). It can be
 * rewritten as this call to the {@link #xq_ret_content xq_ret_content} method:
 *<pre>
 * SELECT javatest.xq_ret_content('/pg_catalog/pg_proc[proname eq $NAME]',
 *                                passing, nullOnEmpty => false)
 * FROM catalog_as_xml,
 * LATERAL (SELECT x AS ".", 'numeric_avg' AS NAME) AS passing;
 *</pre>
 *<p>
 * In the rewritten form, the type of value returned is determined by which
 * function is called, and the parameters to pass to the query are moved out to
 * a separate {@code SELECT} that supplies their values, types, and names (with
 * the context item now given the name ".") and is passed by its alias into the
 * query function.
 *<p>
 * In the standard, parameters and results (of XML types) can be passed
 * {@code BY VALUE} or {@code BY REF}, where the latter means that the same
 * nodes will retain their XQuery node identities over calls (note that this is
 * a meaning unrelated to what "by value" and "by reference" usually mean in
 * PostgreSQL's documentation). PostgreSQL's implementation of the XML type
 * provides no way for {@code BY REF} semantics to be implemented, so everything
 * happening here happens {@code BY VALUE} implicitly, and does not need to be
 * specified.
 * @author Chapman Flack
 */
public class S9
{
	private S9() { }

	/** For verifying clauses "the <identifier> shall be an XML 1.1 NCName." */
	static final Pattern s_NCName_pattern;
	static final Connection s_dbc;
	static final Processor s_s9p = new Processor(false);
	static final ItemTypeFactory s_itf = new ItemTypeFactory(s_s9p);

	enum XMLBinary { HEX, BASE64 };
	enum Nulls { ABSENT, NIL };

	static
	{
		// https://www.w3.org/TR/2006/REC-xml11-20060816/#NT-NameStartChar
		String noColonNameStartChar = "A-Z" + "_" + "a-z" +
			"\\x{C0}-\\x{D6}" + "\\x{D8}-\\x{F6}" + "\\x{F8}-\\x{2FF}" +
			"\\x{370}-\\x{37D}" + "\\x{37F}-\\x{1FFF}"+	"\\x{200C}-\\x{200D}" +
			"\\x{2070}-\\x{218F}" + "\\x{2C00}-\\x{2FEF}" +
			"\\x{3001}-\\x{D7FF}" + "\\x{F900}-\\x{FDCF}" +
			"\\x{FDF0}-\\x{FFFD}" + "\\x{10000}-\\x{EFFFF}";
		s_NCName_pattern = Pattern.compile(
			"[" + noColonNameStartChar + "]"+
			"[" + "-.0-9\\x{B7}\\x{0300}-\\x{036F}\\x{203F}-\\x{2040}" +
			noColonNameStartChar + "]*+");

		try
		{
			s_dbc =	DriverManager.getConnection("jdbc:default:connection");
		}
		catch ( SQLException e )
		{
			throw new ExceptionInInitializerError(e);
		}
	}

	/**
	 * A simple example corresponding to {@code XMLQUERY(expression
	 * PASSING BY VALUE passing RETURNING CONTENT {NULL|EMPTY} ON EMPTY).
	 * @param expression An XQuery expression. Must not be {@code null} (in the
	 * SQL standard {@code XMLQUERY} syntax, it is not even allowed to be an
	 * expression at all, only a string literal).
	 * @param passing A row value whose columns will be supplied to the query
	 * as parameters. Columns with names (typically supplied with {@code AS})
	 * appear as predeclared external variables with matching names (in no
	 * namespace) in the query, with types derived from the SQL types of the
	 * row value's columns. There may be one (and no more than one)
	 * column with {@code AS "."} which, if present, will be bound as the
	 * context item. (The name {@code ?column?}, which PostgreSQL uses for an
	 * otherwise-unnamed column, is also accepted, which will often allow the
	 * context item to be specified with no {@code AS} at all. Beware, though,
	 * that PostgreSQL likes to invent column names from any function or type
	 * name that may appear in the value expression, so this shorthand will not
	 * always work, while {@code AS "."} will.) JDBC uppercases all column
	 * names, so any uses of the corresponding variables in the query must have
	 * the names in upper case.
	 * @param nullOnEmpty pass {@code true} to get a null return in place of
	 * an empty sequence, or {@code false} to just get the empty sequence.
	 */
	@Function(
		schema="javatest",
		onNullInput=CALLED,
		settings="IntervalStyle TO iso_8601"
	)
	public static SQLXML xq_ret_content(
		String expression, ResultSet passing, boolean nullOnEmpty)
		throws SQLException
	{
		try
		{
			return _xq_ret_content(expression, passing, nullOnEmpty);
		}
		catch ( SaxonApiException e )
		{
			throw new SQLException(e.getMessage(), "10000", e);
		}
		catch ( XPathException e )
		{
			throw new SQLException(e.getMessage(), "10000", e);
		}
	}

	private static SQLXML _xq_ret_content(
		String expression, ResultSet passing, boolean nullOnEmpty)
		throws SQLException, SaxonApiException, XPathException
	{
		/*
		 * The expression itself may not be null (in the standard, it isn't
		 * even allowed to be dynamic, and can only be a string literal!).
		 */
		if ( null == expression )
			throw new SQLDataException(
				"XMLQUERY expression may not be null", "22004");

		/*
		 * Get an XQueryCompiler with the static context properly set up.
		 */
		Map<String,Integer> nameToIdx = new HashMap<String,Integer>();
		XQueryCompiler xqc =
			createStaticContextWithPassedTypes(passing, nameToIdx);

		XQueryExecutable xqx = xqc.compile(expression);

		/*
		 * This method implements the RETURNING CONTENT case.
		 *
		 * Now for the General Rules.
		 */
		XQueryEvaluator xqe = xqx.load();
		DocumentBuilder dBuilder = s_s9p.newDocumentBuilder();

		/*
		 * Is there or is there not a context item?
		 */
		if ( ! nameToIdx.containsKey(null) )
		{
			/* "... there is no context item in XDC." */
		}
		else
		{
			Object cve = passing.getObject(nameToIdx.remove(null));
			if ( null == cve )
				return null;
			XdmValue ci;
			if ( cve instanceof SQLXML ) // XXX support SEQUENCE input someday
				ci = dBuilder.build(((SQLXML)cve).getSource(null));
			else
				ci = xmlCastAsSequence(
					cve, XMLBinary.HEX, xqc.getRequiredContextItemType());
			switch ( ci.size() )
			{
			case 0:
				/* "... there is no context item in XDC." */
				break;
			case 1:
				xqe.setContextItem(ci.itemAt(0));
				break;
			default:
				throw new SQLDataException(
					"invalid XQuery context item", "2200V");
			}
		}

		/*
		 * For each <XML query variable> XQV:
		 * (the ResultSetMetaData is only needed here in a quick'n'dirty
		 * workaround of the difficulty of retrieving the already-computed
		 * static types of the variables. sigh.)
		 */
		ResultSetMetaData rsmd = passing.getMetaData();
		for ( Map.Entry<String,Integer> e : nameToIdx.entrySet() )
		{
			String name = e.getKey();
			int i = e.getValue();
			Object v = passing.getObject(i);
			XdmValue vv;
			if ( null == v )
				vv = XdmEmptySequence.getInstance();
			else if ( v instanceof SQLXML ) // XXX support SEQUENCE someday
				vv = dBuilder.build(((SQLXML)v).getSource(null));
			else
			{
				/*
				 * The SequenceType that was determined for the variable has
				 * been set in the (below s9api, underlying) static query
				 * context, but there's no easy way to retrieve it from there
				 * (it is of an underlying, non-s9api class). The method to
				 * determineXQueryFormalType isn't very costly in this
				 * implementation (as it doesn't bother generating the Schema
				 * snippets), so for now, quick 'n' dirty, just do it again.
				 */
				ItemType it = determineXQueryFormalType(rsmd, i).getItemType();
				vv = xmlCastAsSequence(v, XMLBinary.HEX, it);
			}
			xqe.setExternalVariable(new QName(name), vv);
		}

		/*
		 * For now, punt on whether the <XQuery expression> is evaluated
		 * with XML 1.1 or 1.0 lexical rules....  XXX
		 */
		 XdmValue x1 = xqe.evaluate();
		 SequenceIterator x1s = x1.getUnderlyingValue().iterate();
		 if ( nullOnEmpty )
		 {
			if ( 0 == ( SequenceIterator.LOOKAHEAD & x1s.getProperties() ) )
				throw new SQLException(
					"nullOnEmpty requested and result sequence lacks lookahead",
					"XX000");
			if ( ! ((LookaheadIterator)x1s).hasNext() )
			{
				x1s.close();
				return null;
			}
		 }

		SQLXML rsx = s_dbc.createSQLXML();
		QueryResult.serializeSequence(
			x1s, s_s9p.getUnderlyingConfiguration(), rsx.setResult(null),
			new Properties());
		return rsx;
	}

	/**
	 * Return a s9api {@link XQueryCompiler XQueryCompiler} with static context
	 * preconfigured as the Syntax Rules dictate.
	 * @param pt The single-row ResultSet representing the passed parameters
	 * and context item, if any.
	 * @param nameToIndex A Map, supplied empty, that on return will map
	 * variable names for the dynamic context to column indices in {@code pt}.
	 * If a context item was supplied, its index will be entered in the map
	 * with the null key.
	 */
	private static XQueryCompiler createStaticContextWithPassedTypes(
		ResultSet pt, Map<String,Integer> nameToIndex)
		throws SQLException, XPathException
	{
		ResultSetMetaData rsmd = pt.getMetaData();
		int nParams = rsmd.getColumnCount();

		/*
		 * Apply syntax rules to the names.
		 */
		Matcher ncName = s_NCName_pattern.matcher("");
		int contextItemIdx = 0;

		for ( int i = 1; i <= nParams; ++i )
		{
			String label = rsmd.getColumnLabel(i);
			if ( "?COLUMN?".equals(label)  ||  ".".equals(label) )
			{
				if ( 0 != contextItemIdx )
				throw new SQLSyntaxErrorException(
					"Context item supplied more than once (at " +
					contextItemIdx + " and " + i + ')', "42712");
				contextItemIdx = i;
				continue;
			}
			if ( ! ncName.reset(label).matches() )
				throw new SQLSyntaxErrorException(
					"Not an XML NCname: \"" + label + '"', "42602");
			Integer was = nameToIndex.put(label, i);
			if ( null != was )
				throw new SQLSyntaxErrorException(
					"Name \"" + label + "\" duplicated at positions " + was +
					" and " + i, "42712");
		}

		/*
		 * Apply syntax rules to the parameter types. This includes (it's weird
		 * what SQL standards call 'syntax') adding their names and static types
		 * to the static context.
		 */
		XQueryCompiler xqc = s_s9p.newXQueryCompiler();
		xqc.declareNamespace(
			"sqlxml", "http://standards.iso.org/iso9075/2003/sqlxml");
		// https://sourceforge.net/p/saxon/mailman/message/20318550/ :
		xqc.declareNamespace("xdt", "http://www.w3.org/2001/XMLSchema");

		/*
		 * This business of predeclaring global external named variables
		 * is not an s9api-level advertised ability in Saxon, hence the
		 * various getUnderlying.../getStructured... methods here to access
		 * the things that make it happen.
		 */
		StaticQueryContext sqc = xqc.getUnderlyingStaticContext();

		for ( Map.Entry<String,Integer> e : nameToIndex.entrySet() )
		{
			String name = e.getKey();
			int i = e.getValue();
			int ct = rsmd.getColumnType(i);
			assertCanCastAsXmlSequence(ct, name);
			SequenceType st = determineXQueryFormalType(rsmd, i);
			sqc.declareGlobalVariable(
				new QName(name).getStructuredQName(),
				st.getUnderlyingSequenceType(), null, true);
		}

		/*
		 * Apply syntax rules to the context item, if any.
		 */
		if ( 0 != contextItemIdx )
		{
			nameToIndex.put(null, contextItemIdx);
			int ct = rsmd.getColumnType(contextItemIdx);
			assertCanCastAsXmlSequence(ct, "(context item)");
			ItemType it = determineXQueryFormalTypeContextItem(
				rsmd, contextItemIdx);
			xqc.setRequiredContextItemType(it);
		}

		return xqc;
	}

	/**
	 * Check that something's type is "convertible to XML(SEQUENCE)
	 * according to the Syntax Rules of ... <XML cast specification>."
	 * That turns out not to be a very high bar; not much is excluded
	 * by those rules except collection, row, structured, or
	 * reference typed <value expression>s.
	 * @param jdbcType The {@link Types JDBC type} to be checked.
	 * @param what A string to include in the exception message if the
	 * check fails.
	 * @throws SQLException if {@code jdbcType} is one of the prohibited types.
	 */
	private static void assertCanCastAsXmlSequence(int jdbcType, String what)
	throws SQLException
	{
		if ( Types.ARRAY == jdbcType || Types.STRUCT == jdbcType
			|| Types.REF == jdbcType )
			throw new SQLSyntaxErrorException(
				"The type of \"" + what + "\" is not suitable for " +
				"XMLCAST to XML(SEQUENCE).", "42804");
	}

	private static SequenceType determineXQueryFormalType(
		ResultSetMetaData rsmd, int columnIndex)
		throws SQLException
	{
		return determineXQueryFormalType(rsmd, columnIndex, false);
	}

	private static ItemType determineXQueryFormalTypeContextItem(
		ResultSetMetaData rsmd, int columnIndex)
		throws SQLException
	{
		SequenceType st = determineXQueryFormalType(rsmd, columnIndex, true);
		assert OccurrenceIndicator.ONE == st.getOccurrenceIndicator();
		return st.getItemType();
	}

	private static SequenceType determineXQueryFormalType(
		ResultSetMetaData rsmd, int columnIndex, boolean forContextItem)
		throws SQLException
	{
		int sd = rsmd.getColumnType(columnIndex);
		OccurrenceIndicator suffix;
		/*
		 * The SQL/XML standard uses a formal type notation straight out of
		 * the XQuery 1.0 and XPath 2.0 Formal Semantics document, and that is
		 * strictly more fine-grained and expressive than anything you can
		 * actually say in the form of XQuery SequenceTypes. This method will
		 * simply return the nearest approximation in the form of a sequence
		 * type; some of the standard's distinct formal type notations will
		 * collapse into the same SequenceType.
		 *  That also means the various cases laid out in the standard will,
		 * here, all simply assign some ItemType to 'it', and therefore the
		 * tacking on of the occurrence suffix can be factored out for the
		 * very end.
		 */
		ItemType it;

		if ( forContextItem )
			suffix = OccurrenceIndicator.ONE;
		// else if sd is XML(SEQUENCE) - we don't have this type yet
		//	suffix = OccurrenceIndicator.ZERO_OR_MORE;
		/*
		 * Go through the motions of checking isNullable, though PL/Java's JDBC
		 * currently hardcodes columnNullableUnknown. Maybe someday it won't.
		 */
		else if ( columnNoNulls == rsmd.isNullable(columnIndex) )
			suffix = OccurrenceIndicator.ONE;
		else
			suffix = OccurrenceIndicator.ZERO_OR_ONE;

		// Define ET... for {DOCUMENT|CONTENT}(XMLSCHEMA) case ... not supported

		// if SD is XML(DOCUMENT(UNTYPED)) - not currently tracked, can't tell
		//	it = s_itf.getDocumentTest(item type for xdt:untyped);
		// else if SD is XML(DOCUMENT(ANY)) - not currently tracked, can't tell
		//	it = s_itf.getDocumentTest(item type for xs:anyType);
		// else if SD is XML(DOCUMENT(XMLSCHEMA)) - unsupported and can't tell
		//	it = s_itf.getDocumentTest(the ET... we didn't define earlier)
		// else if SD is XML(CONTENT(UNTYPED)) - which we're not tracking ...
		//	at s9api granularity, there's no test for this that's not same as:
		// else if SD is XML(CONTENT(ANY)) - which we must assume for ANY XML
		if ( Types.SQLXML == sd )
			it = s_itf.getNodeKindTest(DOCUMENT);
		// else if SD is XML(CONTENT(XMLSCHEMA)) - we don't track and can't tell
		//	at s9api granularity, there's no test that means this anyway.
		// else if SD is XML(SEQUENCE) - we really should have this type, but no
		//	it = it.ANY_ITEM
		else // it ain't XML, it's some SQL type
		{
			ItemType xmlt = mapSQLDataTypeToXMLSchemaDataType(
				sd, rsmd, columnIndex, XMLBinary.HEX, Nulls.ABSENT);
			// ItemType pt = xmlt.getUnderlyingItemType().getPrimitiveType()
			//  .somehowGetFromUnderlyingPTBackToS9apiPT() - ugh, the hard part
			/*
			 * The intention here is to replace any derived type with the
			 * primitive type it is based on, *except* for three types that are
			 * technically derived: integer (from decimal), yearMonthDuration
			 * and dayTimeDuration (from duration). Those are not replaced, so
			 * they stand, as if they were honorary primitive types.
			 *
			 * For now, it's simplified greatly by mapSQLDataType... skipping
			 * the construction of a whole derived XML Schema snippet, and just
			 * returning the type we want anyway. Also, no need to dive under
			 * the s9api layer to try to make getPrimitiveType work.
			 */
			it = xmlt;
		}

		SequenceType xftn = SequenceType.makeSequenceType(it, suffix);
		return xftn;
	}

	private static ItemType mapSQLDataTypeToXMLSchemaDataType(
		int sd, ResultSetMetaData rsmd, int columnIndex,
		XMLBinary xmlbinary, Nulls nulls)
		throws SQLException
	{
		/*
		 * Nearly all of the fussing about specified in the standard
		 * for this method is to create XML Schema derived types that
		 * accurately reflect the typmod information for the SQL type
		 * in question. Then, in determineXQueryFormalType (the only
		 * client of this method so far!), all of that is thrown away
		 * and our painstakingly specified derived type is replaced with
		 * the primitive type we based it on. That simplifies a lot. :)
		 * For now, forget the derived XML Schema declarations, and just
		 * return the primitive types thwy would be based on.
		 *
		 * The need for the nulls parameter vanishes if no XML Schema snippets
		 * are to be generated.
		 *
		 * If the full XML Schema snippet generation ever proves to be
		 * needed, one hacky way to get it would be with a SELECT
		 * query_to_xmlschema('SELECT null::type-in-question', false, false,
		 * '') where the same derivations are already implemented (though it
		 * produces some different results; that work may have been done from
		 * an earlier version of the standard).
		 */
		switch ( sd )
		{
		case Types.CHAR:
		case Types.VARCHAR:
		case Types.CLOB:
			return ItemType.STRING;

		case Types.BINARY:
		case Types.VARBINARY:
		case Types.BLOB:
			return XMLBinary.HEX == xmlbinary ?
				ItemType.HEX_BINARY : ItemType.BASE64_BINARY;

		case Types.NUMERIC:
		case Types.DECIMAL:
			/*
			 * Go through the motions to get the scale and do this right,
			 * though PL/Java's getScale currently hardcodes a -1 return.
			 * Maybe someday it won't.
			 */
			int scale = rsmd.getScale(columnIndex);
			return 0 == scale ? ItemType.INTEGER : ItemType.DECIMAL;

		case Types.INTEGER:
			return ItemType.INT;
		case Types.SMALLINT:
			return ItemType.SHORT;
		case Types.BIGINT:
			return ItemType.LONG;

		case Types.FLOAT:
			assert false; // PG should always report either REAL or DOUBLE
		case Types.REAL:
			return ItemType.FLOAT; // could check P, MINEXP, MAXEXP here.
		case Types.DOUBLE:
			return ItemType.DOUBLE;

		case Types.BOOLEAN:
			return ItemType.BOOLEAN;

		case Types.DATE:
			return ItemType.DATE;

		case Types.TIME:
			return ItemType.TIME;

		case Types.TIME_WITH_TIMEZONE:
			return ItemType.TIME; // restrictive facet would make sense here

		case Types.TIMESTAMP:
			return ItemType.DATE_TIME;

		case Types.TIMESTAMP_WITH_TIMEZONE:
			return ItemType.DATE_TIME_STAMP; // xsd 1.1 equivalent of facet!

		// There's no JDBC Types.INTERVAL; handle it after switch

		// Good luck finding out from JDBC if it's a domain

		// PG doesn't have DISTINCT types per se

		// PL/Java's JDBC doesn't support PostgreSQL's arrays as ARRAY

		// PG doesn't seem to have multisets (JDBC doesn't grok them either)

		// Types.SQLXML we could recognize, but for determineFormalTypes it has
		// been handled already, and it's not yet clear what would be
		// appropriate to return (short of the specified XMLSchema snippet),
		// probably just document.

		// So punt all these for now; what hasn't been handled in this switch
		// can be handled specially after the switch falls through, and what
		// isn't, isn't supported just now.
		}

		String typeName = rsmd.getColumnTypeName(columnIndex);
		if ( "interval".equals(typeName) )
		{
			/*
			 * XXX This isn't right yet; it needs to be refined to a
			 * YEAR_MONTH_DURATION or a DAY_TIME_DURATION in the appropriate
			 * cases, and for that it needs access to the typmod information
			 * for the type, which getColumnTypeName doesn't now provide.
			 */
			return ItemType.DURATION;
		}

		throw new SQLNonTransientException(String.format(
			"Mapping SQL type \"%s\" to XML type not supported", typeName),
			"0N000");
	}

	/**
	 * Implement that portion of the {@code <XML cast>} specification where
	 * the target data type is sequence, and (for now, anyway) the source is
	 * not an XML type; the only caller, so far, handles that case separately.
	 * @param v The SQL value to be cast (in the form of an Object from JDBC).
	 * @param enc Whether binary values should be encoded in hex or base 64.
	 * @param xst The formal static XS type derived from the SQL type of v.
	 * @return An {@code XdmValue}, {@code null} if {@code v} is null.
	 */
	private static XdmValue xmlCastAsSequence(
		Object v, XMLBinary enc, ItemType xst)
		throws SQLException
	{
		if ( null == v )
			return null;
		/*
		 * What happens next in the standard is one of the most breathtaking
		 * feats of obscurantism in the whole document. It begins, plausibly
		 * enough, by using mapValuesOfSQLTypesToValuesOfXSTypes to produce
		 * the lexical form of the XS type (but with XML metacharacters escaped,
		 * if it's a string type). Then:
		 * 1. That lexical form is to be fed to an XML parser, producing an
		 *	  XQuery document node that NEVER can be a well-formed document (it
		 *	  is expected to satisfy document { text ? } where the text node is
		 *	  just the lexical value form we started with, now with the escaped
		 *	  metacharacters unescaped again as a consequence of parsing). For
		 *	  some source types, mapValuesOfSQLTypesToValuesOfXSTypes can
		 *	  produce a string that parses to XML with element content: row
		 *	  types, arrays, multisets, XML. Clearly, those cases can't satisfy
		 *	  the formal type assumed here, and they are cases this routine
		 *	  won't be expected to handle: XML handled separately by the caller,
		 *	  arrays/structs/etc. being ruled out by assertCanCastAsXmlSequence.
		 * 2. That document node is made the $TEMP parameter of an XML Query,
		 *    '$TEMP cast as XSBTN' (where XSBTN is a QName for the result type
		 *    chosen according to the rules) and the sequence resulting from
		 *    that query is the result of the cast.
		 *
		 * Step (1) can only succeed if the XML parser doesn't insist on well-
		 * formed documents, as the stock JRE parser emphatically does. And the
		 * ultimate effect of that whole dance is that the cast in (2) casts a
		 * document node to the target type, which means the document node gets
		 * atomized, which, for a document node, means everything is thrown away
		 * save the concatenated values of its descendant text nodes (or node,
		 * in this case; haven't we seen that value somewhere before?), assigned
		 * the type xs:untypedAtomic, and then that's operated on by the cast.
		 *
		 * Because this implementation's in PL/Java, the value v received here
		 * has already been mapped from an SQL type to a Java type according to
		 * JDBC's rules as PL/Java implements them, so there's one degree of
		 * removal from the specified algorithm anyway. And the s9api
		 * XdmAtomicValue already has constructors from several of the expected
		 * Java types, as well as one taking a lexical form and explicit type.
		 * Beause this is /example/ code, rather than slavishly implementing the
		 * specified algorithm, it will assume that that is either roughly or
		 * exactly equivalent to what these s9api constructors in fact do, and
		 * just use them; conformance-testing code could then check for exact
		 * equivalence if there's enough interest to write it.
		 *
		 * So, we will NOT start with this:
		 *
		 *	 String xmlv = mapValuesOfSQLTypesToValuesOfXSTypes(
		 *	 	 v, enc, Nulls.ABSENT, true);
		 *
		 * Instead, we'll derive this type first ...
		 */
		ItemType xsbt;
		// year-month interval type => xsbt = YEAR_MONTH_DURATION
		// day-time interval type => xsbt = DAY_TIME_DURATION
		xsbt = xst; // we have a winner!
		// xs non-built-in atomic type => xsbt = getPrimitiveType(ugh).

		/*
		 * ... and then use this method instead:
		 */
		try
		{
			return mapJDBCofSQLvalueToXdmAtomicValue(v, enc, xsbt);
		}
		catch ( SaxonApiException e )
		{
			throw new SQLException(e.getMessage(), "10000", e);
		}
	}

	private static XdmAtomicValue mapJDBCofSQLvalueToXdmAtomicValue(
		Object dv, XMLBinary encoding, ItemType xst)
		throws SQLException, SaxonApiException
	{
		if ( ItemType.STRING.equals(xst) )
			return new XdmAtomicValue((String)dv);

		if ( ItemType.HEX_BINARY.equals(xst) )
			return makeAtomicValue(new HexBinaryValue((byte[])dv));
		if ( ItemType.BASE64_BINARY.equals(xst) )
			return makeAtomicValue(new Base64BinaryValue((byte[])dv));

		if ( ItemType.INTEGER.equals(xst) )
			return new XdmAtomicValue(((BigInteger)dv).toString(), xst);
		if ( ItemType.DECIMAL.equals(xst) )
			return new XdmAtomicValue((BigDecimal)dv);
		if ( ItemType.INT.equals(xst) )
			return new XdmAtomicValue((Integer)dv);
		if ( ItemType.SHORT.equals(xst) )
			return new XdmAtomicValue((Short)dv);
		if ( ItemType.LONG.equals(xst) )
			return new XdmAtomicValue((Long)dv);
		if ( ItemType.FLOAT.equals(xst) )
			return new XdmAtomicValue((Float)dv);
		if ( ItemType.DOUBLE.equals(xst) )
			return new XdmAtomicValue((Double)dv);

		if ( ItemType.BOOLEAN.equals(xst) )
			return new XdmAtomicValue((Boolean)dv);

		if ( ItemType.DATE.equals(xst) )
			return new XdmAtomicValue(((Date)dv).toString(), xst);

		if ( ItemType.TIME.equals(xst) ) // XXX with/without tz matters here...
			return new XdmAtomicValue(((Time)dv).toString(), xst);

		if ( ItemType.DATE_TIME.equals(xst) )
			return new XdmAtomicValue( // trust me, there's only one space
				((Timestamp)dv).toString().replace(' ', 'T'), xst);

		if ( ItemType.DATE_TIME_STAMP.equals(xst) ) // XXX here too...
			return new XdmAtomicValue(
				((Timestamp)dv).toString().replace(' ', 'T'), xst);

		if ( ItemType.DURATION.equals(xst) )
			return new XdmAtomicValue((String)dv, xst);

		throw new SQLNonTransientException(String.format(
			"Mapping SQL value to XML type \"%s\" not supported", xst),
			"0N000");
	}
}