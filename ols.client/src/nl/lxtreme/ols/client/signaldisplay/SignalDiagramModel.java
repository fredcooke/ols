/*
 * OpenBench LogicSniffer / SUMP project 
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or (at
 * your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110, USA
 *
 * Copyright (C) 2006-2010 Michael Poppitz, www.sump.org
 * Copyright (C) 2010 J.W. Janssen, www.lxtreme.nl
 */
package nl.lxtreme.ols.client.signaldisplay;


import java.awt.*;
import java.beans.*;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.event.*;

import nl.lxtreme.ols.client.*;
import nl.lxtreme.ols.client.signaldisplay.cursor.*;
import nl.lxtreme.ols.client.signaldisplay.signalelement.*;
import nl.lxtreme.ols.client.signaldisplay.signalelement.SignalElementManager.*;
import nl.lxtreme.ols.client.signaldisplay.view.*;
import nl.lxtreme.ols.common.*;
import nl.lxtreme.ols.common.acquisition.*;
import nl.lxtreme.ols.common.acquisition.Cursor;


/**
 * The main model for the {@link SignalDiagramComponent}.
 */
public final class SignalDiagramModel
{
  // INNER TYPES

  /**
   * Denotes where to draw the signal, at the top, center or bottom of the
   * channel.
   */
  public static enum SignalAlignment
  {
    TOP, CENTER, BOTTOM;
  }

  // CONSTANTS

  private static final int SNAP_CURSOR_MODE = ( 1 << 0 );
  private static final int MEASUREMENT_MODE = ( 1 << 1 );

  private static final double TIMESTAMP_FACTOR = 100.0;

  // VARIABLES

  private volatile int mode;
  private volatile int selectedChannelIndex;
  private volatile AcquisitionData data;

  private final SignalDiagramController controller;
  private final ZoomController zoomController;
  private final SignalElementManager channelGroupManager;
  private final EventListenerList eventListeners;
  private final PropertyChangeSupport propertyChangeSupport;

  // CONSTRUCTORS

  /**
   * Creates a new SignalDiagramModel instance.
   * 
   * @param aController
   *          the controller to use, cannot be <code>null</code>.
   */
  public SignalDiagramModel( final SignalDiagramController aController )
  {
    this.controller = aController;
    this.zoomController = new ZoomController( aController );

    this.channelGroupManager = new SignalElementManager();

    this.eventListeners = new EventListenerList();
    this.propertyChangeSupport = new PropertyChangeSupport( this );

    this.mode = 0;

    addDataModelChangeListener( this.channelGroupManager );
  }

  // METHODS

  /**
   * Adds a cursor change listener.
   * 
   * @param aListener
   *          the listener to add, cannot be <code>null</code>.
   */
  public void addCursorChangeListener( final ICursorChangeListener aListener )
  {
    this.eventListeners.add( ICursorChangeListener.class, aListener );
  }

  /**
   * Adds a data model change listener.
   * 
   * @param aListener
   *          the listener to add, cannot be <code>null</code>.
   */
  public void addDataModelChangeListener( final IDataModelChangeListener aListener )
  {
    this.eventListeners.add( IDataModelChangeListener.class, aListener );
  }

  /**
   * Adds a measurement listener.
   * 
   * @param aListener
   *          the listener to add, cannot be <code>null</code>.
   */
  public void addMeasurementListener( final IMeasurementListener aListener )
  {
    this.eventListeners.add( IMeasurementListener.class, aListener );
  }

  /**
   * Adds a property change listener.
   * 
   * @param aListener
   *          the listener to add, cannot be <code>null</code>.
   */
  public void addPropertyChangeListener( final PropertyChangeListener aListener )
  {
    this.propertyChangeSupport.addPropertyChangeListener( aListener );
  }

  /**
   * @param aChannelIdx
   * @param aTimestamp
   * @return
   */
  public long findEdgeAfter( final SignalElement aSignalElement, final long aTimestamp )
  {
    final long[] timestamps = getTimestamps();
    final int[] values = getValues();

    int refIdx = Arrays.binarySearch( timestamps, aTimestamp );
    if ( refIdx < 0 )
    {
      refIdx = -( refIdx + 1 ) - 1;
    }

    if ( ( refIdx < 0 ) || ( refIdx >= values.length ) )
    {
      return timestamps[0];
    }

    // find the reference time value; which is the "timestamp" under the
    // cursor...
    final int mask = aSignalElement.getMask();
    final int refValue = ( values[refIdx] & mask );

    do
    {
      refIdx++;
    }
    while ( ( refIdx < ( values.length - 1 ) ) && ( ( values[refIdx] & mask ) == refValue ) );

    return timestamps[refIdx];
  }

  /**
   * @param aChannelIdx
   * @param aTimestamp
   * @return
   */
  public long findEdgeBefore( final SignalElement aSignalElement, final long aTimestamp )
  {
    final long[] timestamps = getTimestamps();
    final int[] values = getValues();

    int refIdx = Arrays.binarySearch( timestamps, aTimestamp );
    if ( refIdx < 0 )
    {
      refIdx = -( refIdx + 1 ) - 1;
    }

    if ( ( refIdx < 0 ) || ( refIdx >= values.length ) )
    {
      return timestamps[0];
    }

    // find the reference time value; which is the "timestamp" under the
    // cursor...
    final int mask = aSignalElement.getMask();
    final int refValue = ( values[refIdx] & mask );

    do
    {
      refIdx--;
    }
    while ( ( refIdx > 0 ) && ( ( values[refIdx] & mask ) == refValue ) );

    return timestamps[Math.max( 0, refIdx )];
  }

  /**
   * Finds a signal element based on a given screen coordinate.
   * 
   * @param aPoint
   *          the coordinate to find the channel for, cannot be
   *          <code>null</code>.
   * @return the channel if found, or <code>null</code> if no channel was found.
   */
  public SignalElement findSignalElement( final Point aPoint )
  {
    final SignalElement[] elements = getSignalElementManager().getSignalElements( aPoint.y, 1,
        SignalElementMeasurer.LOOSE_MEASURER );
    if ( elements.length == 0 )
    {
      return null;
    }
    return elements[0];
  }

  /**
   * @param aPoint
   */
  public void fireMeasurementEvent( final MeasurementInfo aMeasurementInfo )
  {
    final IMeasurementListener[] listeners = this.eventListeners.getListeners( IMeasurementListener.class );
    for ( IMeasurementListener listener : listeners )
    {
      if ( listener.isListening() )
      {
        listener.handleMeasureEvent( aMeasurementInfo );
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  public long getAbsoluteLength()
  {
    if ( !hasData() )
    {
      return 0L;
    }

    final AcquisitionData capturedData = getCapturedData();
    return capturedData.getAbsoluteLength();
  }

  /**
   * Returns the absolute height of the screen.
   * 
   * @return a screen height, in pixels, >= 0 && < {@value Integer#MAX_VALUE}.
   */
  public int getAbsoluteScreenHeight()
  {
    return getSignalElementManager().calculateScreenHeight();
  }

  /**
   * Returns the absolute width of the screen.
   * 
   * @return a screen width, in pixels, >= 0 && < {@value Integer#MAX_VALUE}.
   */
  public int getAbsoluteScreenWidth()
  {
    final double result = Math.floor( getAbsoluteLength() * getZoomFactor() );
    if ( result > Integer.MAX_VALUE )
    {
      return Integer.MAX_VALUE;
    }
    return ( int )result;
  }

  /**
   * @return the acquired data, can be <code>null</code>.
   */
  public AcquisitionData getCapturedData()
  {
    return this.data;
  }

  /**
   * Returns the cursor with the given index.
   * 
   * @param aIndex
   *          the index of the cursor to retrieve, >= 0.
   * @return a cursor element, never <code>null</code>.
   */
  public CursorElement getCursor( final int aIndex )
  {
    return new CursorElement( this.data.getCursor( aIndex ) );
  }

  /**
   * Returns all defined cursors.
   * 
   * @return an array of defined cursors, never <code>null</code>.
   */
  public CursorElement[] getDefinedCursors()
  {
    List<CursorElement> result = new ArrayList<CursorElement>();

    if ( hasData() )
    {
      for ( int i = 0; i < Ols.MAX_CURSORS; i++ )
      {
        Cursor c = this.data.getCursor( i );
        if ( c.isDefined() )
        {
          result.add( new CursorElement( c ) );
        }
      }
    }

    return result.toArray( new CursorElement[result.size()] );
  }

  /**
   * Returns the time interval displayed by the current view.
   * 
   * @return a time interval, in seconds.
   */
  public Double getDisplayedTimeInterval()
  {
    final Rectangle visibleRect = this.controller.getSignalDiagram().getVisibleViewSize();
    if ( ( visibleRect == null ) || !hasData() )
    {
      return null;
    }
    double result;
    if ( hasTimingData() )
    {
      result = visibleRect.width / ( getZoomFactor() * getSampleRate() );
    }
    else
    {
      result = visibleRect.width / getZoomFactor();
    }
    return Double.valueOf( result );
  }

  /**
   * Calculates the horizontal block increment.
   * <p>
   * The following rules are adhered for scrolling horizontally:
   * </p>
   * <ol>
   * <li>unless the first or last sample is not shown, scroll a full block;
   * otherwise</li>
   * <li>do not scroll.</li>
   * </ol>
   * 
   * @param aVisibleRect
   *          the visible rectangle of the component, never <code>null</code>;
   * @param aDirection
   *          the direction in which to scroll (&gt; 0 to scroll left, &lt; 0 to
   *          scroll right);
   * @return a horizontal block increment, determined according to the rules
   *         described.
   */
  public int getHorizontalBlockIncrement( final Rectangle aVisibleRect, final int aDirection )
  {
    final int blockIncr = 50;

    final int firstVisibleSample = locationToSampleIndex( aVisibleRect.getLocation() );
    final int lastVisibleSample = locationToSampleIndex( new Point( aVisibleRect.x + aVisibleRect.width, 0 ) );
    final int lastSampleIdx = getSampleCount();

    int inc = 0;
    if ( aDirection < 0 )
    {
      // Scroll left
      if ( firstVisibleSample >= 0 )
      {
        inc = blockIncr;
      }
    }
    else if ( aDirection > 0 )
    {
      // Scroll right
      if ( lastVisibleSample < lastSampleIdx )
      {
        inc = blockIncr;
      }
    }

    return inc;
  }

  /**
   * {@inheritDoc}
   */
  public int getSampleRate()
  {
    final AcquisitionData capturedData = getCapturedData();
    if ( capturedData == null )
    {
      return -1;
    }
    return getCapturedData().getSampleRate();
  }

  /**
   * {@inheritDoc}
   */
  public int getSampleWidth()
  {
    final AcquisitionData capturedData = getCapturedData();
    if ( capturedData == null )
    {
      return 0;
    }
    return capturedData.getChannelCount();
  }

  /**
   * Returns the current selected channel.
   * 
   * @return the selected channel, or <code>null</code> if no channel is
   *         selected.
   */
  public SignalElement getSelectedChannel()
  {
    return getSignalElementManager().getChannelByIndex( this.selectedChannelIndex );
  }

  /**
   * Returns the signal element representing the digital channel with the given
   * index.
   * 
   * @param aIndex
   *          the index of the signal element to return, >= 0.
   * @return a signal element, can be <code>null</code>.
   */
  public SignalElement getSignalElement( final int aIndex )
  {
    return getSignalElementManager().getChannelByIndex( aIndex );
  }

  /**
   * Returns channel group manager.
   * 
   * @return the channel group manager, never <code>null</code>.
   */
  public SignalElementManager getSignalElementManager()
  {
    return this.channelGroupManager;
  }

  /**
   * Returns the hover area of the signal under the given coordinate (= mouse
   * position).
   * 
   * @param aPoint
   *          the mouse coordinate to determine the signal rectangle for, cannot
   *          be <code>null</code>.
   * @return the rectangle of the signal the given coordinate contains,
   *         <code>null</code> if not found.
   */
  public MeasurementInfo getSignalHover( final Point aPoint )
  {
    final SignalElement signalElement = findSignalElement( aPoint );
    if ( ( signalElement == null ) || !signalElement.isDigitalSignal() )
    {
      // Trivial reject: no digital signal, or not above any channel...
      return null;
    }

    return getSignalHover( aPoint, signalElement );
  }

  /**
   * @param aPoint
   * @param aSignalElement
   * @return
   */
  public MeasurementInfo getSignalHover( final Point aPoint, final SignalElement aSignalElement )
  {
    if ( !aSignalElement.isDigitalSignal() )
    {
      throw new IllegalArgumentException( "Signal element must represent a digital channel!" );
    }

    final int refIdx = locationToSampleIndex( aPoint );
    // Calculate the "absolute" time based on the mouse position, use a
    // "over sampling" factor to allow intermediary (between two time stamps)
    // time value to be shown...
    final double refTime;
    if ( hasTimingData() )
    {
      refTime = getTimestamp( aPoint );
    }
    else
    {
      refTime = refIdx;
    }

    final Channel channel = aSignalElement.getChannel();
    if ( !channel.isEnabled() )
    {
      // Trivial reject: real channel is invisible...
      return new MeasurementInfo( aSignalElement, refTime );
    }

    final long[] timestamps = getTimestamps();

    long ts = -1L;
    long tm = -1L;
    long te = -1L;
    long th = -1L;

    // find the reference time value; which is the "timestamp" under the
    // cursor...
    final int[] values = getValues();
    if ( ( refIdx >= 0 ) && ( refIdx < values.length ) )
    {
      final int mask = channel.getMask();
      final int refValue = ( values[refIdx] & mask );

      int idx = refIdx;
      do
      {
        idx--;
      }
      while ( ( idx >= 0 ) && ( ( values[idx] & mask ) == refValue ) );

      // convert the found index back to "screen" values...
      final int tm_idx = Math.max( 0, idx + 1 );
      tm = ( tm_idx == 0 ) ? 0 : timestamps[tm_idx];

      // Search for the original value again, to complete the pulse...
      do
      {
        idx--;
      }
      while ( ( idx >= 0 ) && ( ( values[idx] & mask ) != refValue ) );

      // convert the found index back to "screen" values...
      final int ts_idx = Math.max( 0, idx + 1 );
      ts = ( ts_idx == 0 ) ? 0 : timestamps[ts_idx];

      idx = refIdx;
      do
      {
        idx++;
      }
      while ( ( idx < values.length ) && ( ( values[idx] & mask ) == refValue ) );

      // convert the found index back to "screen" values...
      final int te_idx = Math.min( idx, timestamps.length - 1 );
      te = ( te_idx == 0 ) ? 0 : timestamps[te_idx];

      // Determine the width of the "high" part...
      if ( ( values[ts_idx] & mask ) != 0 )
      {
        th = Math.abs( tm - ts );
      }
      else
      {
        th = Math.abs( te - tm );
      }
    }

    MeasurementInfo result;
    if ( hasTimingData() )
    {
      result = new MeasurementInfo( aSignalElement, ts, tm, te, th, refTime, getZoomFactor(), getSampleRate() );
    }
    else
    {
      result = new MeasurementInfo( aSignalElement, ts, tm, te, refTime, getZoomFactor(), getSampleRate() );
    }

    return result;
  }

  /**
   * Returns the amount of pixels that represents one second on the timeline.
   * 
   * @return the number of pixels to display for 1 second, > 0.
   */
  public Double getTimelinePixelsPerSecond()
  {
    if ( !hasData() || !hasTimingData() )
    {
      return null;
    }
    return Double.valueOf( getZoomFactor() * getSampleRate() );
  }

  /**
   * Returns the number of seconds that represents one pixel on the timeline.
   * 
   * @return the number of seconds per pixel, > 0.
   * @see #getTimelinePixelsPerSecond()
   */
  public Double getTimelineSecondsPerPixel()
  {
    Double pixelsPerSecond = getTimelinePixelsPerSecond();
    if ( pixelsPerSecond == null )
    {
      return null;
    }
    return Double.valueOf( 1.0 / pixelsPerSecond.doubleValue() );
  }

  /**
   * Returns the unit of time the timeline is currently is displaying, in
   * multiples of 10 (for human readability).
   * 
   * @return a timeline unit of time, > 0, can only be <code>null</code> if
   *         there is no data.
   */
  public Double getTimelineUnitOfTime()
  {
    final Double p = getTimelineSecondsPerPixel();
    if ( p == null )
    {
      return null;
    }
    return Double.valueOf( Math.pow( 10, Math.ceil( Math.log10( p.doubleValue() ) ) ) );
  }

  /**
   * Converts the X-coordinate of the given {@link Point} to a precise
   * timestamp, useful for display purposes.
   * 
   * @param aAbsTimestamp
   *          the timestamp to convert to a relative timestamp, should be >= 0.
   * @return a precise timestamp, as double value.
   * @see DisplayUtils#displayTime(double)
   */
  public double getTimestamp( long aAbsTimestamp )
  {
    // Calculate the "absolute" time based on the mouse position, use a
    // "over sampling" factor to allow intermediary (between two time stamps)
    // time value to be shown...
    final double zoomFactor = getZoomFactor();
    final double scaleFactor = TIMESTAMP_FACTOR * zoomFactor;

    // Take (optional) trigger position into account...
    final Long triggerPos = getTriggerPosition();
    if ( triggerPos != null )
    {
      aAbsTimestamp -= triggerPos.longValue();
    }
    // If no sample rate is available, we use a factor of 1; which doesn't
    // make a difference in the result...
    final int sampleRate = Math.max( 1, getSampleRate() );

    return ( scaleFactor * aAbsTimestamp ) / ( scaleFactor * sampleRate );
  }

  /**
   * Converts the X-coordinate of the given {@link Point} to a precise
   * timestamp, useful for display purposes.
   * 
   * @param aPoint
   *          the X,Y-coordinate to convert to a precise timestamp, cannot be
   *          <code>null</code>.
   * @return a precise timestamp, as double value.
   * @see DisplayUtils#displayTime(double)
   */
  public double getTimestamp( final Point aPoint )
  {
    // Calculate the "absolute" time based on the mouse position, use a
    // "over sampling" factor to allow intermediary (between two time stamps)
    // time value to be shown...
    final double zoomFactor = getZoomFactor();
    final double scaleFactor = TIMESTAMP_FACTOR * zoomFactor;

    // Convert mouse position to absolute timestamp...
    double x = aPoint.x / zoomFactor;
    // Take (optional) trigger position into account...
    final Long triggerPos = getTriggerPosition();
    if ( triggerPos != null )
    {
      x -= triggerPos.longValue();
    }
    // If no sample rate is available, we use a factor of 1; which doesn't
    // make a difference in the result...
    if ( !hasTimingData() )
    {
      return ( scaleFactor * x ) / scaleFactor;
    }

    return ( scaleFactor * x ) / ( scaleFactor * getSampleRate() );
  }

  /**
   * {@inheritDoc}
   */
  public int getTimestampIndex( final long aValue )
  {
    final AcquisitionData capturedData = getCapturedData();
    if ( capturedData == null )
    {
      return 0;
    }
    return capturedData.getSampleIndex( aValue );
  }

  /**
   * {@inheritDoc}
   */
  public long[] getTimestamps()
  {
    final AcquisitionData capturedData = getCapturedData();
    if ( capturedData == null )
    {
      return new long[0];
    }
    return capturedData.getTimestamps();
  }

  /**
   * Returns the trigger position, if available.
   * 
   * @return a trigger position, as timestamp, or <code>null</code> if no
   *         trigger is used/present.
   */
  public Long getTriggerPosition()
  {
    AcquisitionData capturedData = getCapturedData();
    if ( ( capturedData == null ) || !capturedData.hasTriggerData() )
    {
      return null;
    }
    return Long.valueOf( capturedData.getTriggerPosition() );
  }

  /**
   * {@inheritDoc}
   */
  public int[] getValues()
  {
    final AcquisitionData capturedData = getCapturedData();
    if ( capturedData == null )
    {
      return new int[0];
    }
    return capturedData.getValues();
  }

  /**
   * Calculates the vertical block increment.
   * <p>
   * The following rules are adhered for scrolling vertically:
   * </p>
   * <ol>
   * <li>if the first shown channel is not completely visible, it will be made
   * fully visible; otherwise</li>
   * <li>scroll down to show the succeeding channel fully;</li>
   * <li>if the last channel is fully shown, and there is some room left at the
   * bottom, show the remaining space.</li>
   * </ol>
   * 
   * @param aVisibleRect
   *          the visible rectangle of the component, never <code>null</code>;
   * @param aDirection
   *          the direction in which to scroll (&gt; 0 to scroll down, &lt; 0 to
   *          scroll up).
   * @return a vertical block increment, determined according to the rules
   *         described.
   */
  public int getVerticalBlockIncrement( final Dimension aViewDimensions, final Rectangle aVisibleRect,
      final int aDirection )
  {
    final SignalElementMeasurer measurer = SignalElementMeasurer.LOOSE_MEASURER;
    final SignalElementManager elemMgr = getSignalElementManager();

    SignalElement[] signalElements = elemMgr.getSignalElements( aVisibleRect.y + 1, 1, measurer );
    if ( signalElements.length == 0 )
    {
      return 0;
    }

    final int spacing = UIManager.getInt( UIManagerKeys.SIGNAL_ELEMENT_SPACING );

    int inc = 0;
    int yPos = signalElements[0].getYposition();

    if ( aDirection > 0 )
    {
      // Scroll down...
      int height = signalElements[0].getHeight() + spacing;
      inc = height - ( aVisibleRect.y - yPos );
      if ( inc < 0 )
      {
        inc = -inc;
      }
    }
    else if ( aDirection < 0 )
    {
      // Scroll up...
      inc = ( aVisibleRect.y - yPos );
      if ( inc <= 0 )
      {
        // Determine the height of the element *before* the current one, as we
        // need to scroll up its height...
        signalElements = elemMgr.getSignalElements( yPos - spacing, 1, measurer );
        if ( signalElements.length > 0 )
        {
          inc += signalElements[0].getHeight() + spacing;
        }
      }
    }

    return inc;
  }

  /**
   * Returns the zoom controller of this diagram.
   * 
   * @return the zoom controller, never <code>null</code>.
   */
  public ZoomController getZoomController()
  {
    return this.zoomController;
  }

  /**
   * Returns the current zoom factor.
   * 
   * @return a zoom factor.
   */
  public double getZoomFactor()
  {
    return getZoomController().getFactor();
  }

  /**
   * Returns whether or not there is captured data to display.
   * 
   * @return <code>true</code> if there is any data to display,
   *         <code>false</code> otherwise.
   */
  public boolean hasData()
  {
    return ( this.data != null ) && ( getCapturedData() != null );
  }

  /**
   * Returns whether the data is a timed-capture or a state-capture.
   * 
   * @return <code>true</code> if there is timing data available,
   *         <code>false</code> if not.
   */
  public boolean hasTimingData()
  {
    AcquisitionData captureData = getCapturedData();
    return ( captureData != null ) && captureData.hasTimingData();
  }

  /**
   * @return <code>true</code> if the analog scope is by default visible,
   *         <code>false</code> if it is default hidden.
   */
  public boolean isAnalogScopeDefaultVisible()
  {
    return UIManager.getBoolean( UIManagerKeys.ANALOG_SCOPE_VISIBLE_DEFAULT );
  }

  /**
   * {@inheritDoc}
   */
  public boolean isCursorDefined( final int aCursorIdx )
  {
    return getCursor( aCursorIdx ).isDefined();
  }

  /**
   * Returns whether or not the cursor-mode is enabled.
   * 
   * @return <code>true</code> if cursor-mode is enabled, thereby making all
   *         defined cursors visible, <code>false</code> otherwise.
   */
  public boolean isCursorMode()
  {
    CursorController controller = Client.getInstance().getCursorController();
    return controller.isCursorsVisible();
  }

  /**
   * @return <code>true</code> if the group summary is by default visible,
   *         <code>false</code> if it is default hidden.
   */
  public boolean isGroupSummaryDefaultVisible()
  {
    return UIManager.getBoolean( UIManagerKeys.GROUP_SUMMARY_VISIBLE_DEFAULT );
  }

  /**
   * @return
   */
  public boolean isMeasurementMode()
  {
    return ( this.mode & MEASUREMENT_MODE ) != 0;
  }

  /**
   * @return <code>true</code> if the snap cursor mode is enabled,
   *         <code>false</code> otherwise.
   */
  public boolean isSnapCursorMode()
  {
    return ( this.mode & SNAP_CURSOR_MODE ) != 0;
  }

  /**
   * Converts the given coordinate to the corresponding sample index.
   * 
   * @param aCoordinate
   *          the coordinate to convert to a sample index, cannot be
   *          <code>null</code>.
   * @return a sample index, >= 0, or -1 if no corresponding sample index could
   *         be found.
   */
  public int locationToSampleIndex( final Point aCoordinate )
  {
    final long timestamp = locationToTimestamp( aCoordinate );
    final int idx = getTimestampIndex( timestamp );
    if ( idx < 0 )
    {
      return -1;
    }
    final int sampleCount = getSampleCount() - 1;
    if ( idx > sampleCount )
    {
      return sampleCount;
    }

    return idx;
  }

  /**
   * Converts the given coordinate to the corresponding sample index.
   * 
   * @param aCoordinate
   *          the coordinate to convert to a sample index, cannot be
   *          <code>null</code>.
   * @return a sample index, >= 0, or -1 if no corresponding sample index could
   *         be found.
   */
  public long locationToTimestamp( final Point aCoordinate )
  {
    final long timestamp = ( long )Math.ceil( aCoordinate.x / getZoomFactor() );
    if ( timestamp < 0 )
    {
      return -1;
    }
    return timestamp;
  }

  /**
   * @param aCursorIdx
   */
  public void removeCursor( final int aCursorIdx )
  {
    final CursorElement cursor = getCursor( aCursorIdx );
    if ( !cursor.isDefined() )
    {
      // Nothing to do; the cursor is not defined...
      return;
    }

    fireCursorRemovedEvent( cursor );

    cursor.clear();
  }

  /**
   * Removes a cursor change listener.
   * 
   * @param aListener
   *          the listener to remove, cannot be <code>null</code>.
   */
  public void removeCursorChangeListener( final ICursorChangeListener aListener )
  {
    this.eventListeners.remove( ICursorChangeListener.class, aListener );
  }

  /**
   * Removes a data model change listener.
   * 
   * @param aListener
   *          the listener to remove, cannot be <code>null</code>.
   */
  public void removeDataModelChangeListener( final IDataModelChangeListener aListener )
  {
    this.eventListeners.remove( IDataModelChangeListener.class, aListener );
  }

  /**
   * Removes the given measurement listener from the list of listeners.
   * 
   * @param aListener
   *          the listener to remove, cannot be <code>null</code>.
   */
  public void removeMeasurementListener( final IMeasurementListener aListener )
  {
    this.eventListeners.remove( IMeasurementListener.class, aListener );
  }

  /**
   * Removes a property change listener.
   * 
   * @param aListener
   *          the listener to remove, cannot be <code>null</code>.
   */
  public void removePropertyChangeListener( final PropertyChangeListener aListener )
  {
    this.propertyChangeSupport.removePropertyChangeListener( aListener );
  }

  /**
   * Sets the data model for this controller.
   * 
   * @param aDataSet
   *          the dataModel to set, cannot be <code>null</code>.
   */
  public void setAcquisitionData( final AcquisitionData aData )
  {
    if ( aData == null )
    {
      throw new IllegalArgumentException( "Data cannot be null!" );
    }

    this.data = aData;

    final IDataModelChangeListener[] listeners = this.eventListeners.getListeners( IDataModelChangeListener.class );
    for ( IDataModelChangeListener listener : listeners )
    {
      listener.dataModelChanged( aData );
    }
  }

  /**
   * {@inheritDoc}
   */
  public void setCursor( final int aCursorIdx, final long aTimestamp )
  {
    final CursorElement cursor = getCursor( aCursorIdx );

    long oldTimestamp = cursor.getTimestamp();
    boolean wasDefined = cursor.isDefined();
    // Update the time stamp of the cursor...
    cursor.setTimestamp( aTimestamp );

    if ( !wasDefined )
    {
      fireCursorAddedEvent( cursor );
    }
    else
    {
      fireCursorMoveEvent( cursor, oldTimestamp, aTimestamp );
    }
  }

  /**
   * Returns the color for a cursor with the given index.
   * 
   * @param aCursorIndex
   *          the index of the cursor to retrieve the color for.
   * @return a cursor color, never <code>null</code>.
   */
  public void setCursorColor( final int aCursorIndex, final Color aColor )
  {
    final CursorElement cursor = getCursor( aCursorIndex );
    cursor.setColor( aColor );

    fireCursorChangeEvent( ICursorChangeListener.PROPERTY_COLOR, cursor );
  }

  /**
   * Returns the color for a cursor with the given index.
   * 
   * @param aCursorIdx
   *          the index of the cursor to retrieve the color for;
   * @param aLabel
   *          the label to set, cannot be <code>null</code>.
   * @return a cursor color, never <code>null</code>.
   */
  public void setCursorLabel( final int aCursorIdx, final String aLabel )
  {
    final CursorElement cursor = getCursor( aCursorIdx );

    // Update the label of the cursor...
    cursor.setLabel( aLabel );

    fireCursorChangeEvent( ICursorChangeListener.PROPERTY_LABEL, cursor );
  }

  /**
   * Enables or disables the cursors.
   * 
   * @param aSelected
   *          <code>true</code> to enable the cursors, <code>false</code> to
   *          disable the cursors.
   */
  public void setCursorMode( final boolean aCursorMode )
  {
    CursorController controller = Client.getInstance().getCursorController();
    controller.setCursorsVisible( aCursorMode );

    ICursorChangeListener[] listeners = this.eventListeners.getListeners( ICursorChangeListener.class );
    for ( ICursorChangeListener listener : listeners )
    {
      if ( aCursorMode )
      {
        listener.cursorsVisible();
      }
      else
      {
        listener.cursorsInvisible();
      }
    }
  }

  /**
   * @param aEnabled
   */
  public void setMeasurementMode( final boolean aEnabled )
  {
    if ( aEnabled )
    {
      this.mode |= MEASUREMENT_MODE;
    }
    else
    {
      this.mode &= ~MEASUREMENT_MODE;
    }

    IMeasurementListener[] listeners = this.eventListeners.getListeners( IMeasurementListener.class );
    for ( IMeasurementListener listener : listeners )
    {
      if ( aEnabled )
      {
        listener.enableMeasurementMode();
      }
      else
      {
        listener.disableMeasurementMode();
      }
    }
  }

  /**
   * Sets the selected channel index to the given value.
   * 
   * @param aChannelIndex
   *          the index to set, or -1 if no channel is to be selected.
   */
  public void setSelectedChannelIndex( final int aChannelIndex )
  {
    this.selectedChannelIndex = aChannelIndex;
  }

  /**
   * @param aSnapCursors
   *          the snapCursor to set
   */
  public void setSnapCursorMode( final boolean aSnapCursors )
  {
    if ( aSnapCursors )
    {
      this.mode |= SNAP_CURSOR_MODE;
    }
    else
    {
      this.mode &= ~SNAP_CURSOR_MODE;
    }
  }

  /**
   * @param aOldCursor
   * @param aCursor
   */
  private void fireCursorAddedEvent( final CursorElement aCursor )
  {
    ICursorChangeListener[] listeners = this.eventListeners.getListeners( ICursorChangeListener.class );
    for ( ICursorChangeListener listener : listeners )
    {
      listener.cursorAdded( aCursor );
    }
  }

  /**
   * @param aOldCursor
   * @param aCursor
   */
  private void fireCursorChangeEvent( final String aPropertyName, final CursorElement aCursor )
  {
    ICursorChangeListener[] listeners = this.eventListeners.getListeners( ICursorChangeListener.class );
    for ( ICursorChangeListener listener : listeners )
    {
      listener.cursorChanged( aPropertyName, aCursor );
    }
  }

  /**
   * @param aOldCursor
   * @param aCursor
   */
  private void fireCursorMoveEvent( final CursorElement aCursor, final long aOldTimestamp, final long aNewTimestamp )
  {
    ICursorChangeListener[] listeners = this.eventListeners.getListeners( ICursorChangeListener.class );
    for ( ICursorChangeListener listener : listeners )
    {
      listener.cursorMoved( aOldTimestamp, aNewTimestamp );
    }
  }

  /**
   * @param aCursor
   */
  private void fireCursorRemovedEvent( final CursorElement aCursor )
  {
    ICursorChangeListener[] listeners = this.eventListeners.getListeners( ICursorChangeListener.class );
    for ( ICursorChangeListener listener : listeners )
    {
      listener.cursorRemoved( aCursor );
    }
  }

  /**
   * {@inheritDoc}
   */
  private int getSampleCount()
  {
    return getValues().length;
  }
}
