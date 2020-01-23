/*
 * Copyright (c) 2016-2020 Tada AB and other contributors, as listed below.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the The BSD 3-Clause License
 * which accompanies this distribution, and is available at
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Contributors:
 *   Chapman Flack
 */
package org.postgresql.pljava.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import static java.lang.invoke.MethodHandles.publicLookup;
import java.lang.invoke.MethodType;
import static java.lang.invoke.MethodType.methodType;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLNonTransientException;
import java.sql.SQLSyntaxErrorException;

import static java.util.Arrays.copyOf;
import static java.util.Collections.addAll;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.postgresql.pljava.ResultSetHandle;
import org.postgresql.pljava.ResultSetProvider;
import static org.postgresql.pljava.internal.Backend.doInPG;
import static org.postgresql.pljava.jdbc.TypeOid.INVALID;
import org.postgresql.pljava.sqlj.Loader;

public class Function
{
	/**
	 * Return null if the {@code prosrc} field in the provided {@code procTup}
	 * does not have the form of a UDT specification; if it does, return the
	 * associated class, loaded with the class loader for {@code schemaName}.
	 */
	public static Class<? extends SQLData> getClassIfUDT(
		ResultSet procTup, String schemaName)
	throws SQLException
	{
		Matcher info = parse(procTup);
		String className = info.group("udtcls");
		if ( null == className )
			return null;
		return
			loadClass(Loader.getSchemaLoader(schemaName), className)
				.asSubclass(SQLData.class);
	}

	/**
	 * Like the original C function of the same name, using effectively the same
	 * inputs, but producing a {@code MethodType} instead of a JNI signature.
	 *<p>
	 * The return type is the last element of {@code jTypes}.
	 */
	private static MethodType buildSignature(
		ClassLoader schemaLoader, String[] jTypes,
		boolean retTypeIsOutParameter, boolean isMultiCall, boolean altForm)
	throws SQLException
	{
		/*
		 * Begin by assuming we won't include the "return" type among the
		 * parameter types.
		 */
		int rtIdx = jTypes.length - 1;
		String retJType = jTypes[rtIdx];

		/*
		 * As things are currently arranged, retTypeIsOutParameter is equivalent
		 * to "the return type is composite" and "the type is ResultSet".
		 */
		assert retTypeIsOutParameter == ("java.sql.ResultSet".equals(retJType));

		/*
		 * And ... if the return type is composite, and this isn't a multi-call,
		 * then it does go at the end of the other parameters.
		 */
		if ( ! isMultiCall  &&  retTypeIsOutParameter )
			++ rtIdx;

		Class<?>[] pTypes = new Class[ rtIdx ];

		for ( int i = 0 ; i < rtIdx ; ++ i )
			pTypes[i] = loadClass(schemaLoader, jTypes[i]);

		Class<?> returnType =
			getReturnSignature(schemaLoader, retJType,
				retTypeIsOutParameter, isMultiCall, altForm);

		return methodType(returnType, pTypes);
	}

	/**
	 * Return a {@code Class} object for the target method's return type.
	 *<p>
	 * The C original in this case was a "virtual method" on {@code Type}, but
	 * only one "subclass" ({@code Composite}) ever overrode the default
	 * behavior. The default (for everything else but {@code Composite}) is to
	 * return the type unchanged in the non-multicall case, or {@code Iterator}
	 * (of that type) for multicall.
	 *<p>
	 * The overridden behavior for a composite type is to return boolean in the
	 * non-multicall case, else one of {@code ResultSetHandle} or
	 * {@code ResultSetProvider} depending on {@code altForm}.
	 */
	private static Class<?> getReturnSignature(
		ClassLoader schemaLoader, String retJType,
		boolean isComposite, boolean isMultiCall, boolean altForm)
	throws SQLException
	{
		if ( ! isComposite )
		{
			if ( isMultiCall )
				return Iterator.class;
			return loadClass(schemaLoader, retJType);
		}

		/* The composite case */
		if ( isMultiCall )
			return altForm ? ResultSetHandle.class : ResultSetProvider.class;
		return boolean.class;
	}

	/**
	 * Replacement for {@code getMethodID} in the C code, but producing a
	 * {@code MethodHandle} instead.
	 *<p>
	 * This is called in the cases where {@code init} would return a non-null
	 * method name: the non-UDT cases. UDT methods are handled their own
	 * special way.
	 *<p>
	 * This may modify the last element (the return type) of the {@code jTypes}
	 * array, in the course of hunting for the right return type of the method.
	 *<p>
	 * For now, this is a near-facsimile of the C implementation. A further step
	 * of refactoring into clearer idiomatic Java can come later.
	 */
	private static MethodHandle getMethodHandle(
		ClassLoader schemaLoader, Class<?> clazz, String methodName,
		String[] jTypes, boolean retTypeIsOutParameter, boolean isMultiCall)
	throws SQLException
	{
		MethodType mt =
			buildSignature(schemaLoader, jTypes, retTypeIsOutParameter,
				isMultiCall, false); // first try altForm = false

		ReflectiveOperationException ex1 = null;
		try
		{
			return publicLookup().findStatic(clazz, methodName, mt);
		}
		catch ( ReflectiveOperationException e )
		{
			ex1 = e;
		}

		MethodType origMT = mt;
		Class<?> altType = null;
		Class<?> realRetType = loadClass(schemaLoader, jTypes[jTypes.length-1]);

		/* COPIED COMMENT:
		 * One valid reason for not finding the method is when
		 * the return type used in the signature is a primitive and
		 * the true return type of the method is the object class that
		 * corresponds to that primitive.
		 */
		if ( realRetType.isPrimitive() )
		{
			altType = methodType(realRetType).wrap().returnType();
			realRetType = altType;
		}

		/* COPIED COMMENT:
		 * Another reason might be that we expected a ResultSetProvider
		 * but the implementation returns a ResultSetHandle that needs to be
		 * wrapped. The wrapping is internal so we retain the original
		 * return type anyway.
		 */
		if ( ResultSet.class == realRetType )
			altType = realRetType;

		if ( null != altType )
		{
			jTypes[jTypes.length - 1] = altType.getCanonicalName();
			mt = buildSignature(schemaLoader, jTypes, retTypeIsOutParameter,
					isMultiCall, true); // this time altForm = true
			try
			{
				return publicLookup().findStatic(clazz, methodName, mt);
			}
			catch ( ReflectiveOperationException e )
			{
				SQLException sqle =
					memberException(clazz, methodName, origMT,true/*isStatic*/);
				sqle.initCause(ex1);
				sqle.setNextException((SQLException)
					memberException(clazz, methodName, mt, true /*isStatic*/)
					.initCause(e));
				throw sqle;
			}	
		}

		throw (SQLException)
			memberException(clazz, methodName, origMT, true /*isStatic*/)
			.initCause(ex1);
	}

	/**
	 * Produce an exception for a class member not found, with a message similar
	 * to that of the C {@code PgObject_throwMemberError}.
	 */
	private static SQLException memberException(
		Class<?> clazz, String name, MethodType mt, boolean isStatic)
	{
		return new SQLNonTransientException(
			String.format("Unable to find%s method %s.%s with signature %s",
				(isStatic ? " static" : ""),
				clazz.getCanonicalName(), name, mt),
			"38000");
	}

	/**
	 * Parse the function specification in {@code procTup}, initializing most
	 * fields of the C {@code Function} structure, and returning the name of the
	 * implementing method, as a {@code String}.
	 */
	public static String create(
		long wrappedPtr, ResultSet procTup, String langName, String schemaName,
		boolean calledAsTrigger)
	throws SQLException
	{
		Matcher info = parse(procTup);

		return init(wrappedPtr, info, procTup, schemaName, calledAsTrigger);
	}

	/**
	 * Retrieve the {@code prosrc} field from the provided {@code procTup}, and
	 * return it parsed as a {@code Matcher} object with named capturing groups.
	 */
	private static Matcher parse(ResultSet procTup) throws SQLException
	{
		String spec = getAS(procTup);

		Matcher m = specForms.matcher(spec);
		if ( ! m.matches() )
			throw new SQLSyntaxErrorException(
				"cannot parse AS string", "42601");

		return m;
	}

	/**
	 * Given the information passed to {@code create} and the {@code Matcher}
	 * object from {@code parse}, determine the type of function being created
	 * (ordinary, UDT, trigger) and initialize most of the C structure
	 * accordingly.
	 * @return the name of the implementing method as a String, or null in the
	 * case of a UDT
	 */
	private static String init(
		long wrappedPtr, Matcher info, ResultSet procTup, String schemaName,
		boolean calledAsTrigger)
	throws SQLException
	{
		Map<Oid,Class<? extends SQLData>> typeMap = null;
		String className = info.group("udtcls");
		boolean isUDT = (null != className);

		if ( ! isUDT )
		{
			className = info.group("cls");
			typeMap = Loader.getTypeMap(schemaName);
		}

		boolean readOnly = ((byte)'v' != procTup.getByte("provolatile"));

		ClassLoader schemaLoader = Loader.getSchemaLoader(schemaName);
		Class<?> clazz = loadClass(schemaLoader, className);

		if ( isUDT )
		{
			setupUDT(wrappedPtr, info, procTup, schemaLoader, clazz, readOnly);
			return null;
		}

		String[] resolvedTypes;
		boolean isMultiCall = false;
		boolean retTypeIsOutParameter = false;

		if ( calledAsTrigger )
		{
			typeMap = null;
			resolvedTypes =	setupTriggerParams(
				wrappedPtr, info, schemaLoader, clazz, readOnly);
		}
		else
		{
			boolean[] multi = new boolean[] { isMultiCall };
			boolean[] rtiop = new boolean[] { retTypeIsOutParameter };
			resolvedTypes = setupFunctionParams(wrappedPtr, info, procTup,
				schemaLoader, clazz, readOnly, typeMap, multi, rtiop);
			isMultiCall = multi [ 0 ];
			retTypeIsOutParameter = rtiop [ 0 ];
		}

		String methodName = info.group("meth");

		System.err.println(
			getMethodHandle(schemaLoader, clazz, methodName,
				resolvedTypes, retTypeIsOutParameter, isMultiCall));

		return methodName;
		/* to do: build signature ... look up method ... store that. */
	}

	/**
	 * The initialization specific to a UDT function.
	 */
	private static void setupUDT(
		long wrappedPtr, Matcher info, ResultSet procTup,
		ClassLoader schemaLoader, Class<?> clazz, boolean readOnly)
	throws SQLException
	{
		String udtFunc = info.group("udtfun");
		int udtInitial = Character.toLowerCase(udtFunc.charAt(0));
		Oid udtId;
		switch ( udtInitial )
		{
		case 'i':
		case 'r':
			udtId = (Oid)procTup.getObject("prorettype");
			break;
		case 'o':
		case 's':
			udtId = ((Oid[])procTup.getObject("proargtypes"))[0];
			break;
		default:
			throw new SQLException("internal error in PL/Java UDT parsing");
		}

		doInPG(() -> _storeToUDT(wrappedPtr, schemaLoader,
			clazz.asSubclass(SQLData.class), readOnly, udtInitial,
			udtId.intValue()));
	}

	/**
	 * The initialization specific to a trigger function.
	 */
	private static String[] setupTriggerParams(
		long wrappedPtr, Matcher info,
		ClassLoader schemaLoader, Class<?> clazz, boolean readOnly)
	throws SQLException
	{
		if ( null != info.group("sig") )
			throw new SQLSyntaxErrorException(
				"Triggers may not have a Java parameter signature", "42601");

		Oid retType = INVALID;
		String retJType = "void";

		Oid[] paramTypes = { INVALID };
		String[] paramJTypes = { "org.postgresql.pljava.TriggerData" };

		return storeToNonUDT(wrappedPtr, schemaLoader, clazz, readOnly,
			false /* isMultiCall */,
			null /* typeMap */, retType, retJType, paramTypes, paramJTypes,
			null /* [returnTypeIsOutputParameter] */);
	}

	/**
	 * The initialization specific to an ordinary function.
	 */
	private static String[] setupFunctionParams(
		long wrappedPtr, Matcher info, ResultSet procTup,
		ClassLoader schemaLoader, Class<?> clazz,
		boolean readOnly, Map<Oid,Class<? extends SQLData>> typeMap,
		boolean[] multi, boolean[] returnTypeIsOP)
		throws SQLException
	{
		int numParams = procTup.getInt("pronargs");
		boolean isMultiCall = procTup.getBoolean("proretset");
		multi [ 0 ] = isMultiCall;
		Oid[] paramTypes = null;

		Oid returnType = (Oid)procTup.getObject("prorettype");

		if ( 0 < numParams )
			paramTypes = (Oid[])procTup.getObject("proargtypes");

		String[] resolvedTypes = storeToNonUDT(wrappedPtr, schemaLoader, clazz,
			readOnly, isMultiCall, typeMap,
			returnType, null /* returnJType */,
			paramTypes, null /* paramJTypes */,
			returnTypeIsOP);

		boolean returnTypeIsOutputParameter = returnTypeIsOP[0];

		String explicitSignature = info.group("sig");
		if ( null != explicitSignature )
		{
			/*
			 * An explicit signature given for the Java method requires a call
			 * to parseParameters to reconcile those types with the ones in
			 * resolvedTypes that the mapping from SQL types suggested above.
			 */
			parseParameters( wrappedPtr, resolvedTypes, explicitSignature,
				isMultiCall, returnTypeIsOutputParameter);
		}

		/* As in the original C setupFunctionParams, if an explicit Java return
		 * type is included in the AS string, now compare it to the previously
		 * resolved return type and adapt if they are different, like what
		 * happened just above in parseParameters for the parameters. A close
		 * look at parseParameters shows it can *also* have adjusted the return
		 * type ... that happens in the case where a composite value is returned
		 * using an appended OUT parameter and the actual function's return
		 * type is boolean. If that happened, the resolved type examined here
		 * will be the one parseParameters just put in - the actual type of the
		 * appended parameter - and if an explicit return type was also given
		 * in AS, that work just done will be overwritten by this to come.
		 * The case is probably one that has never come up in practice; it's
		 * probably not useful, but at the moment I am trying to duplicate the
		 * original behavior.
		 */

		String explicitReturnType = info.group("ret");
		if ( null != explicitReturnType )
		{
			String resolvedReturnType = resolvedTypes[resolvedTypes.length - 1];
			if ( ! explicitReturnType.equals(resolvedReturnType) )
			{
				/* Once again overload the reconcileTypes native method with a
				 * very slightly different behavior, this one keyed by index -2.
				 * In this case, its explicitTypes parameter will be a one-
				 * element array containing only the return type ... and the
				 * coercer, if needed, will be constructed with getCoerceOut
				 * instead of getCoerceIn.
				 */
				doInPG(() -> _reconcileTypes(wrappedPtr, resolvedTypes,
					new String[] { explicitReturnType }, -2));
			}
		}

		return resolvedTypes;
	}

	/**
	 * Apply the legacy PL/Java rules for matching the types in the SQL
	 * declaration of the function with those in the Java method signature.
	 */
	private static void parseParameters(
		long wrappedPtr, String[] resolvedTypes, String explicitSignature,
		boolean isMultiCall, boolean returnTypeIsOutputParameter)
		throws SQLException
	{
		boolean lastIsOut = ( ! isMultiCall ) && returnTypeIsOutputParameter;
		String[] explicitTypes = explicitSignature.isEmpty() ?
			new String[0] : COMMA.split(explicitSignature);

		int expect = resolvedTypes.length - (lastIsOut ? 0 : 1);

		if ( expect != explicitTypes.length )
			throw new SQLSyntaxErrorException(String.format(
				"AS (Java): expected %1$d parameter types, found %2$d",
				expect, explicitTypes.length), "42601");

		doInPG(() ->
		{
			for ( int i = 0 ; i < resolvedTypes.length - 1 ; ++ i )
			{
				if ( resolvedTypes[i].equals(explicitTypes[i]) )
					continue;
				_reconcileTypes(wrappedPtr, resolvedTypes, explicitTypes, i);
			}
		});

		if ( lastIsOut
			&& ! resolvedTypes[expect-1].equals(explicitTypes[expect-1]) )
		{
			/* Use the same reconcileTypes native method to handle the return
			 * type also ... its behavior must change a bit, so use index -1 to
			 * identify this case.
			 */
			doInPG(() ->
				_reconcileTypes(wrappedPtr, resolvedTypes, explicitTypes, -1));
		}
	}

	/**
	 * Pattern for splitting an explicit signature on commas, relying on
	 * whitespace already being stripped by {@code getAS}. Will not match
	 * consecutive, leading, or trailing commas.
	 */
	private static final Pattern COMMA = Pattern.compile("(?<=[^,]),(?=[^,])");

	/**
	 * Return a class given a loader to use and a canonical type name, as used
	 * in explicit signatures in the AS string. Just a bit of gymnastics to
	 * turn that form of name into the right class, including for primitives,
	 * void, and arrays.
	 */
	private static Class<?> loadClass(
		ClassLoader schemaLoader, String className)
	throws SQLException
	{
		Matcher m = typeNameInAS.matcher(className);
		m.matches();
		className = m.group(1);
		Class<?> c;

		switch ( className )
		{
		case "boolean": c = boolean.class; break;
		case    "byte": c =    byte.class; break;
		case   "short": c =   short.class; break;
		case     "int": c =     int.class; break;
		case    "long": c =    long.class; break;
		case    "char": c =    char.class; break;
		case   "float": c =   float.class; break;
		case  "double": c =  double.class; break;
		case    "void": c =    void.class; break;
		default:
			try
			{
				c = schemaLoader.loadClass(className);
			}
			catch ( ClassNotFoundException e )
			{
				throw new SQLNonTransientException(
					"No such class: " + className, "46103", e);
			}
		}

		if ( -1 != m.start(2) )
		{
			int ndims = (m.end(2) - m.start(2)) / 2;
			c = Array.newInstance(c, new int[ndims]).getClass();
		}

		return c;
	}

	/**
	 * Get the "AS" string (also known as the {@code prosrc} field of the
	 * {@code pg_proc} tuple), with whitespace stripped, and with an {@code =}
	 * separating the return type, if any, from the method name, per the rules
	 * of the earlier C implementation.
	 */
	private static String getAS(ResultSet procTup) throws SQLException
	{
		String spec = procTup.getString("prosrc"); // has NOT NULL constraint

		/* COPIED COMMENT */
		/* Strip all whitespace except the first one if it occures after
		 * some alpha numeric characers and before some other alpha numeric
		 * characters. We insert a '=' when that happens since it delimits
		 * the return value from the method name.
		 */
		/* ANALYZED COMMENT */
		/* Original code skipped every isspace() character encountered while
		 * atStart or passedFirst was true. Initially true, atStart was reset
		 * by the first non-isspace character. Initially false, passedFirst
		 * was set by ANY encounter of a non-isspace non-isalnum, OR of any
		 * non-isspace following at least one isspace AFTER atStart was reset.
		 * The = was added if the non-isspace character satisfied isalpha.
		 */
		spec = stripEarlyWSinAS.matcher(spec).replaceFirst("$2=");
		spec = stripOtherWSinAS.matcher(spec).replaceAll("");
		return spec;
	}


	/**
	 * Pattern used to strip early whitespace in an "AS" string.
	 */
	private static final Pattern stripEarlyWSinAS = Pattern.compile(
		"^(\\s*+)(\\p{Alnum}++)(\\s*+)(?=\\p{Alpha})"
	);

	/**
	 * Pattern used to strip the remaining whitespace in an "AS" string.
	 */
	private static final Pattern stripOtherWSinAS = Pattern.compile(
		"\\s*+"
	);

	/**
	 * The recognized forms of an "AS" string, distinguishable and broken out
	 * by named capturing groups.
	 */
	private static final Pattern specForms = Pattern.compile(
		"(?i:udt\\[(?<udtcls>[^]]++)\\](?<udtfun>input|output|receive|send))" +
		"|(?!(?i:udt\\[))" +
		"(?:(?<ret>[^=]++)=)?+(?<cls>(?:[^.(]++\\.?)+)\\.(?<meth>[^.(]++)" +
		"(?:\\((?<sig>[^)]*+)\\))?+"
	);

	/**
	 * The recognized form of a Java type name in an "AS" string.
	 * The first capturing group is the canonical name of a type; the second
	 * group, if present, matches one or more {@code []} array markers following
	 * the name (its length divided by two is the number of array dimensions).
	 */
	private static final Pattern typeNameInAS = Pattern.compile(
		"([^\\[]++)((?:\\[\\])++)?+"
	);

	/**
	 * Test whether the type {@code t0} is, directly or indirectly,
	 * a specialization of generic type {@code c0}.
	 * @param t0 a type to be checked
	 * @param c0 known generic type to check for
	 * @return null if {@code t0} does not extend {@code c0}, otherwise the
	 * array of type arguments with which it specializes {@code c0}
	 */
	private static Type[] specialization(Type t0, Class<?> c0)
	{
		Type t = t0;
		Class<?> c;
		ParameterizedType pt = null;
		TypeBindings latestBindings = null;
		Type[] actualArgs = null;

		if ( t instanceof Class )
		{
			c = (Class)t;
			if ( ! c0.isAssignableFrom(c) )
				return null;
			if ( c0 == c )
				return new Type[0];
		}
		else if ( t instanceof ParameterizedType )
		{
			pt = (ParameterizedType)t;
			c = (Class)pt.getRawType();
			if ( ! c0.isAssignableFrom(c) )
				return null;
			if ( c0 == c )
				actualArgs = pt.getActualTypeArguments();
			else
				latestBindings = new TypeBindings(null, pt);
		}
		else
			throw new AssertionError(
				"expected Class or ParameterizedType, got: " + t);

		if ( null == actualArgs )
		{
			List<Type> pending = new LinkedList();
			pending.add(c.getGenericSuperclass());
			addAll(pending, c.getGenericInterfaces());

			while ( ! pending.isEmpty() )
			{
				t = pending.remove(0);
				if ( null == t )
					continue;
				if ( t instanceof Class )
				{
					c = (Class)t;
					if ( c0 == c )
						return new Type[0];
				}
				else if ( t instanceof ParameterizedType )
				{
					pt = (ParameterizedType)t;
					c = (Class)pt.getRawType();
					if ( c0 == c )
					{
						actualArgs = pt.getActualTypeArguments();
						break;
					}
					if ( c0.isAssignableFrom(c) )
						pending.add(new TypeBindings(latestBindings, pt));
				}
				else if ( t instanceof TypeBindings )
				{
					latestBindings = (TypeBindings)t;
					continue;
				}
				else
					throw new AssertionError(
						"expected Class or ParameterizedType, got: " + t);
				if ( ! c0.isAssignableFrom(c) )
					continue;
				pending.add(c.getGenericSuperclass());
				addAll(pending, c.getGenericInterfaces());
			}
		}
		if ( null == actualArgs )
			throw new AssertionError(
				"failed checking whether " + t0 + " specializes " + c0);

		for ( int i = 0; i < actualArgs.length; ++ i )
			if ( actualArgs[i] instanceof TypeVariable )
				actualArgs[i] =
					latestBindings.resolve((TypeVariable)actualArgs[i]);

		return actualArgs;
	}

	/**
	 * A class recording the bindings made in a ParameterizedType to the type
	 * parameters in a GenericDeclaration<Class>. Implements {@code Type} so it
	 * can be added to the {@code pending} queue in {@code specialization}.
	 *<p>
	 * In {@code specialization}, the tree of superclasses/superinterfaces will
	 * be searched breadth-first, with all of a node's immediate supers enqueued
	 * before any from the next level. By recording a node's type variable to
	 * type argument bindings in an object of this class, and enqueueing it
	 * before any of the node's supers, any type variables encountered as actual
	 * type arguments to any of those supers should be resolvable in the object
	 * of this class most recently dequeued.
	 */
	static class TypeBindings implements Type
	{
		private final TypeVariable<?>[] formalTypeParams;
		private final Type[] actualTypeArgs;

		TypeBindings(TypeBindings prior, ParameterizedType pt)
		{
			actualTypeArgs = pt.getActualTypeArguments();
			formalTypeParams =
				((GenericDeclaration)pt.getRawType()).getTypeParameters();
			assert actualTypeArgs.length == formalTypeParams.length;

			if ( null == prior )
				return;

			for ( int i = 0; i < actualTypeArgs.length; ++ i )
			{
				Type t = actualTypeArgs[i];
				if ( actualTypeArgs[i] instanceof TypeVariable )
					actualTypeArgs[i] = prior.resolve((TypeVariable)t);
			}
		}

		Type resolve(TypeVariable v)
		{
			for ( int i = 0; i < formalTypeParams.length; ++ i )
				if ( formalTypeParams[i].equals(v) )
					return actualTypeArgs[i];
			throw new AssertionError("type binding not found for " + v);
		}
	}

	/**
	 * Wrap the native method to store the values computed in Java, for a
	 * non-UDT function, into the C {@code Function} structure. Returns an array
	 * of Java type names for the parameters, if any, as suggested by the C code
	 * based on the SQL types, and can indicate whether the method return type
	 * is an out parameter, if a one-element array of boolean is passed to
	 * receive that result.
	 */
	private static String[] storeToNonUDT(
		long wrappedPtr, ClassLoader schemaLoader, Class<?> clazz,
		boolean readOnly, boolean isMultiCall,
		Map<Oid,Class<? extends SQLData>> typeMap,
		Oid returnType, String returnJType, Oid[] paramTypes, String[] pJTypes,
		boolean[] returnTypeIsOutParameter)
	{
		int numParams;
		int[] paramOids;
		if ( null == paramTypes )
		{
			numParams = 0;
			paramOids = null;
		}
		else
		{
			numParams = paramTypes.length;
			paramOids = new int [ numParams ];
			for ( int i = 0 ; i < numParams ; ++ i )
				paramOids[i] = paramTypes[i].intValue();
		}

		String[] outJTypes = new String [ 1 + numParams ];

		boolean rtiop =
			doInPG(() -> _storeToNonUDT(
				wrappedPtr, schemaLoader, clazz, readOnly, isMultiCall, typeMap,
				numParams, returnType.intValue(), returnJType, paramOids,
				pJTypes, outJTypes));

		if ( null != returnTypeIsOutParameter )
			returnTypeIsOutParameter[0] = rtiop;

		return outJTypes;
	}

	private static native boolean _storeToNonUDT(
		long wrappedPtr, ClassLoader schemaLoader, Class<?> clazz,
		boolean readOnly, boolean isMultiCall,
		Map<Oid,Class<? extends SQLData>> typeMap,
		int numParams, int returnType, String returnJType,
		int[] paramTypes, String[] paramJTypes, String[] outJTypes);

	private static native void _storeToUDT(
		long wrappedPtr, ClassLoader schemaLoader,
		Class<? extends SQLData> clazz,
		boolean readOnly, int funcInitial, int udtOid);

	private static native void _reconcileTypes(
		long wrappedPtr, String[] resolvedTypes, String[] explicitTypes, int i);
}