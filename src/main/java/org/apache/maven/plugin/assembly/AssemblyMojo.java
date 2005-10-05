package org.apache.maven.plugin.assembly;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.resolver.filter.AndArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ExcludesArtifactFilter;
import org.apache.maven.artifact.resolver.filter.IncludesArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.assembly.model.Assembly;
import org.apache.maven.plugins.assembly.model.DependencySet;
import org.apache.maven.plugins.assembly.model.FileSet;
import org.apache.maven.plugins.assembly.model.io.xpp3.AssemblyXpp3Reader;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.codehaus.plexus.archiver.tar.TarArchiver;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.introspection.ReflectionValueExtractor;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assemble an application bundle or distribution from an assembly descriptor.
 *
 * @author <a href="mailto:brett@apache.org">Brett Porter</a>
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 * @version $Id$
 * @goal assembly
 * @requiresDependencyResolution test
 * @requiresDirectInvocation
 * @execute phase="package"
 * @aggregator
 */
public class AssemblyMojo
    extends AbstractUnpackingMojo
{
    /**
     * Predefined Assembly Descriptor Id's.  You can select bin, jar-with-dependencies, or src.
     *
     * @parameter expression="${descriptorId}"
     */
    protected String descriptorId;

    /**
     * Assembly XML Descriptor file.  This must be the path to your customized descriptor file.
     *
     * @parameter expression="${descriptor}"
     */
    protected File descriptor;

    /**
     * Base directory of the project.
     *
     * @parameter expression="${basedir}"
     * @required
     * @readonly
     */
    private String basedir;

    /**
     * The Maven Project.
     *
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * Maven ProjectHelper
     *
     * @component
     */
    private MavenProjectHelper projectHelper;

    /**
     * Temporary directory that contain the files to be assembled.
     *
     * @parameter expression="${project.build.directory}/archive-tmp"
     * @required
     * @readonly
     */
    private File tempRoot;

    /**
     * Create the binary distribution.
     *
     * @throws MojoExecutionException
     */
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        Assembly assembly = readAssembly();

        // TODO: include dependencies marked for distribution under certain formats
        // TODO: how, might we plug this into an installer, such as NSIS?
        // TODO: allow file mode specifications?

        String fullName = finalName + "-" + assembly.getId();

        for ( Iterator i = assembly.getFormats().iterator(); i.hasNext(); )
        {
            String format = (String) i.next();

            String filename = fullName + "." + format;

            File destFile;
            try
            {
                Archiver archiver = createArchiver( format );

                destFile = createArchive( archiver, assembly, filename );
            }
            catch ( NoSuchArchiverException e )
            {
                throw new MojoFailureException( "Unable to obtain archiver for extension '" + format + "'" );
            }
            catch ( ArchiverException e )
            {
                throw new MojoExecutionException( "Error creating assembly", e );
            }
            catch ( IOException e )
            {
                throw new MojoExecutionException( "Error creating assembly", e );
            }

            projectHelper.attachArtifact( project, format, format + "-assembly", destFile );
        }
    }

    protected File createArchive( Archiver archiver, Assembly assembly, String filename )
        throws ArchiverException, IOException, MojoExecutionException, MojoFailureException
    {
        File destFile;
        processDependencySets( archiver, assembly.getDependencySets(), assembly.isIncludeBaseDirectory() );
        processFileSets( archiver, assembly.getFileSets(), assembly.isIncludeBaseDirectory() );

        destFile = new File( outputDirectory, filename );
        archiver.setDestFile( destFile );
        archiver.createArchive();

        return destFile;
    }

    protected Assembly readAssembly()
        throws MojoFailureException, MojoExecutionException
    {
        Reader r;

        if ( descriptor != null )
        {
            try
            {
                r = new FileReader( descriptor );
            }
            catch ( FileNotFoundException e )
            {
                throw new MojoFailureException( "Unable to find descriptor: " + e.getMessage() );
            }
        }
        else if ( descriptorId != null )
        {
            InputStream resourceAsStream = getClass().getResourceAsStream( "/assemblies/" + descriptorId + ".xml" );
            if ( resourceAsStream == null )
            {
                throw new MojoFailureException( "Descriptor with ID '" + descriptorId + "' not found" );
            }
            r = new InputStreamReader( resourceAsStream );
        }
        else
        {
            throw new MojoFailureException( "You must specify descriptor or descriptorId" );
        }

        Assembly assembly;
        try
        {
            AssemblyXpp3Reader reader = new AssemblyXpp3Reader();
            assembly = reader.read( r );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Error reading descriptor", e );
        }
        catch ( XmlPullParserException e )
        {
            throw new MojoExecutionException( "Error reading descriptor", e );
        }
        finally
        {
            IOUtil.close( r );
        }
        return assembly;
    }

    /**
     * Processes Dependency Sets
     *
     * @param archiver
     * @param dependencySets
     * @param includeBaseDirectory
     */
    protected void processDependencySets( Archiver archiver, List dependencySets, boolean includeBaseDirectory )
        throws ArchiverException, IOException, MojoExecutionException, MojoFailureException
    {
        for ( Iterator i = dependencySets.iterator(); i.hasNext(); )
        {
            DependencySet dependencySet = (DependencySet) i.next();
            String output = dependencySet.getOutputDirectory();
            output = getOutputDirectory( output, includeBaseDirectory );

            archiver.setDefaultDirectoryMode( Integer.parseInt( dependencySet.getDirectoryMode(), 8 ) );

            archiver.setDefaultFileMode( Integer.parseInt( dependencySet.getFileMode(), 8 ) );

            getLog().debug( "DependencySet[" + output + "]" + " dir perms: " +
                Integer.toString( archiver.getDefaultDirectoryMode(), 8 ) + " file perms: " +
                Integer.toString( archiver.getDefaultFileMode(), 8 ) );

            AndArtifactFilter filter = new AndArtifactFilter();
            filter.add( new ScopeArtifactFilter( dependencySet.getScope() ) );

            if ( !dependencySet.getIncludes().isEmpty() )
            {
                filter.add( new IncludesArtifactFilter( dependencySet.getIncludes() ) );
            }
            if ( !dependencySet.getExcludes().isEmpty() )
            {
                filter.add( new ExcludesArtifactFilter( dependencySet.getExcludes() ) );
            }

            // TODO: includes and excludes
            for ( Iterator j = dependencies.iterator(); j.hasNext(); )
            {
                Artifact artifact = (Artifact) j.next();

                if ( filter.include( artifact ) )
                {
                    String name = artifact.getFile().getName();

                    if ( dependencySet.isUnpack() )
                    {
                        // TODO: something like zipfileset in plexus-archiver
//                        archiver.addJar(  )

                        File tempLocation = new File( workDirectory, name.substring( 0, name.length() - 4 ) );
                        boolean process = false;
                        if ( !tempLocation.exists() )
                        {
                            tempLocation.mkdirs();
                            process = true;
                        }
                        else if ( artifact.getFile().lastModified() > tempLocation.lastModified() )
                        {
                            process = true;
                        }

                        if ( process )
                        {
                            unpack( artifact.getFile(), tempLocation );
                        }
                        archiver.addDirectory( tempLocation, null,
                                               (String[]) getDefaultExcludes().toArray( EMPTY_STRING_ARRAY ) );
                    }
                    else
                    {
                        archiver.addFile( artifact.getFile(), output +
                            evaluateFileNameMapping( dependencySet.getOutputFileNameMapping(), artifact ) );
                    }
                }
            }
        }
    }

    /**
     * Process Files that will be included in the distribution.
     *
     * @param archiver
     * @param fileSets
     * @param includeBaseDirecetory
     * @throws ArchiverException
     */
    protected void processFileSets( Archiver archiver, List fileSets, boolean includeBaseDirecetory )
        throws ArchiverException
    {
        for ( Iterator i = fileSets.iterator(); i.hasNext(); )
        {
            FileSet fileSet = (FileSet) i.next();
            String directory = fileSet.getDirectory();
            String output = fileSet.getOutputDirectory();

            String lineEnding = getLineEndingCharacters( fileSet.getLineEnding() );

            File tmpDir = null;

            if ( lineEnding != null )
            {
                tmpDir = FileUtils.createTempFile( "", "", tempRoot );
                tmpDir.mkdirs();
            }

            archiver.setDefaultDirectoryMode( Integer.parseInt( fileSet.getDirectoryMode(), 8 ) );

            archiver.setDefaultFileMode( Integer.parseInt( fileSet.getFileMode(), 8 ) );

            getLog().debug( "FileSet[" + output + "]" + " dir perms: " +
                Integer.toString( archiver.getDefaultDirectoryMode(), 8 ) + " file perms: " +
                Integer.toString( archiver.getDefaultFileMode(), 8 ) +
                ( fileSet.getLineEnding() == null ? "" : " lineEndings: " + fileSet.getLineEnding() ) );

            if ( directory == null )
            {
                directory = basedir;
                if ( output == null )
                {
                    output = "";
                }
            }
            else
            {
                if ( output == null )
                {
                    output = directory;
                }
            }
            output = getOutputDirectory( output, includeBaseDirecetory );

            String[] includes = (String[]) fileSet.getIncludes().toArray( EMPTY_STRING_ARRAY );
            if ( includes.length == 0 )
            {
                includes = null;
            }

            // TODO: default excludes should be in the archiver?
            List excludesList = fileSet.getExcludes();
            excludesList.addAll( getDefaultExcludes() );
            String[] excludes = (String[]) excludesList.toArray( EMPTY_STRING_ARRAY );

            File archiveBaseDir = new File( directory );

            if ( lineEnding != null )
            {
                copySetReplacingLineEndings( archiveBaseDir, tmpDir, includes, excludes, lineEnding );

                archiveBaseDir = tmpDir;
            }

            archiver.addDirectory( archiveBaseDir, output, includes, excludes );
        }
    }

    /**
     * Evaluates Filename Mapping
     *
     * @param expression
     * @param artifact
     * @return expression
     */
    private static String evaluateFileNameMapping( String expression, Artifact artifact )
        throws MojoExecutionException
    {
        // this matches the last ${...} string
        Pattern pat = Pattern.compile( "^(.*)\\$\\{([^\\}]+)\\}(.*)$" );
        Matcher mat = pat.matcher( expression );

        String left;
        String right;
        Object middle;

        if ( mat.matches() )
        {
            left = evaluateFileNameMapping( mat.group( 1 ), artifact );
            try
            {
                middle = ReflectionValueExtractor.evaluate( "dep." + mat.group( 2 ), artifact );
            }
            catch ( Exception e )
            {
                throw new MojoExecutionException( "Cannot evaluate filenameMapping", e );
            }
            right = mat.group( 3 );

            if ( middle == null )
            {
                // TODO: There should be a more generic way dealing with that. Having magic words is not good at all.
                // probe for magic word
                if ( "extension".equals( mat.group( 2 ).trim() ) )
                {
                    ArtifactHandler artifactHandler = artifact.getArtifactHandler();
                    middle = artifactHandler.getExtension();
                }
                else
                {
                    middle = "${" + mat.group( 2 ) + "}";
                }
            }

            return left + middle + right;
        }

        return expression;
    }

    /**
     * Get the Output Directory by parsing the String output directory.
     *
     * @param output The string representation of the output directory.
     * @param includeBaseDirectory True if base directory is to be included in the assembled file.
     */
    private String getOutputDirectory( String output, boolean includeBaseDirectory )
    {
        if ( output == null )
        {
            output = "";
        }
        if ( !output.endsWith( "/" ) && !output.endsWith( "\\" ) )
        {
            // TODO: shouldn't archiver do this?
            output += '/';
        }

        if ( includeBaseDirectory )
        {
            if ( output.startsWith( "/" ) )
            {
                output = finalName + output;
            }
            else
            {
                output = finalName + "/" + output;
            }
        }
        else
        {
            if ( output.startsWith( "/" ) )
            {
                output = output.substring( 1 );
            }
        }
        return output;
    }

    /**
     * Creates the necessary archiver to build the distribution file.
     *
     * @param format Archive format
     * @return archiver  Archiver generated
     * @throws ArchiverException
     */
    private Archiver createArchiver( String format )
        throws ArchiverException, NoSuchArchiverException
    {
        Archiver archiver;
        if ( format.startsWith( "tar" ) )
        {
            TarArchiver tarArchiver = (TarArchiver) this.archiverManager.getArchiver( "tar" );
            archiver = tarArchiver;
            int index = format.indexOf( '.' );
            if ( index >= 0 )
            {
                // TODO: this needs a cleanup in plexus archiver - use a real typesafe enum
                TarArchiver.TarCompressionMethod tarCompressionMethod = new TarArchiver.TarCompressionMethod();
                // TODO: this should accept gz and bz2 as well so we can skip over the switch
                String compression = format.substring( index + 1 );
                if ( "gz".equals( compression ) )
                {
                    tarCompressionMethod.setValue( "gzip" );
                }
                else if ( "bz2".equals( compression ) )
                {
                    tarCompressionMethod.setValue( "bzip2" );
                }
                else
                {
                    // TODO: better handling
                    throw new IllegalArgumentException( "Unknown compression format: " + compression );
                }
                tarArchiver.setCompression( tarCompressionMethod );
            }
        }
        else
        {
            archiver = this.archiverManager.getArchiver( format );
        }
        return archiver;
    }

    /**
     * Insert into the exclude list the default excludes file pattern.
     *
     * @return defaultExcludes List containing the default patterns of files to be excluded.
     */
    public static List getDefaultExcludes()
    {
        List defaultExcludes = new ArrayList();
        defaultExcludes.add( "**/*~" );
        defaultExcludes.add( "**/#*#" );
        defaultExcludes.add( "**/.#*" );
        defaultExcludes.add( "**/%*%" );
        defaultExcludes.add( "**/._*" );

        // CVS
        defaultExcludes.add( "**/CVS" );
        defaultExcludes.add( "**/CVS/**" );
        defaultExcludes.add( "**/.cvsignore" );

        // SCCS
        defaultExcludes.add( "**/SCCS" );
        defaultExcludes.add( "**/SCCS/**" );

        // Visual SourceSafe
        defaultExcludes.add( "**/vssver.scc" );

        // Subversion
        defaultExcludes.add( "**/.svn" );
        defaultExcludes.add( "**/.svn/**" );

        // Mac
        defaultExcludes.add( "**/.DS_Store" );

        return defaultExcludes;
    }

    private void copyReplacingLineEndings( File source, File dest, String lineEndings )
        throws IOException
    {
        getLog().debug( "Copying while replacing line endings: " + source + " to " + dest );

        BufferedReader in = new BufferedReader( new FileReader( source ) );
        BufferedWriter out = new BufferedWriter( new FileWriter( dest ) );

        String line;

        while ( ( line = in.readLine() ) != null )
        {
            out.write( line );
            out.write( lineEndings );
        }
        out.flush();
        out.close();
    }


    private void copySetReplacingLineEndings( File archiveBaseDir, File tmpDir, String[] includes, String[] excludes,
                                              String lineEnding )
        throws ArchiverException
    {
        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir( archiveBaseDir.getAbsolutePath() );
        scanner.setIncludes( includes );
        scanner.setExcludes( excludes );
        scanner.scan();

        String [] dirs = scanner.getIncludedDirectories();

        for ( int j = 0; j < dirs.length; j ++ )
        {
            new File( tempRoot, dirs[j] ).mkdirs();
        }

        String [] files = scanner.getIncludedFiles();

        for ( int j = 0; j < files.length; j ++ )
        {
            File targetFile = new File( tmpDir, files[j] );

            try
            {
                targetFile.getParentFile().mkdirs();

                copyReplacingLineEndings( new File( archiveBaseDir, files[j] ), targetFile, lineEnding );
            }
            catch ( IOException e )
            {
                throw new ArchiverException( "Error copying file '" + files[j] + "' to '" + targetFile + "'", e );
            }
        }

    }

    private static String getLineEndingCharacters( String lineEnding )
        throws ArchiverException
    {
        if ( lineEnding != null )
        {
            if ( "keep".equals( lineEnding ) )
            {
                lineEnding = null;
            }
            else if ( "dos".equals( lineEnding ) || "crlf".equals( lineEnding ) )
            {
                lineEnding = "\r\n";
            }
            else if ( "unix".equals( lineEnding ) || "lf".equals( lineEnding ) )
            {
                lineEnding = "\n";
            }
            else
            {
                throw new ArchiverException( "Illlegal lineEnding specified: '" + lineEnding + "'" );
            }
        }

        return lineEnding;
    }


}
