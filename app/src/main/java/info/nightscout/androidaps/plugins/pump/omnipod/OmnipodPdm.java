package info.nightscout.androidaps.plugins.pump.omnipod;

import android.content.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.TimeZone;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.plugins.general.overview.events.EventDismissNotification;
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification;
import info.nightscout.androidaps.plugins.pump.omnipod.api.OmnipodStatus;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmniCoreBasalScheduleRequest;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmniCoreBolusRequest;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmniCoreCancelBolusRequest;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmniCoreCancelTempBasalRequest;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmniCoreStatusRequest;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmniCoreTempBasalRequest;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.pump.omnipod.events.EventOmnipodUpdateGui;
import info.nightscout.androidaps.utils.SP;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmniCoreResult;

public class OmnipodPdm {

    private final Context _context;
    private OmniCoreResult _lastResult;
    private OmnipodStatus _lastStatus;
    private int _lastKnownBatteryLevel = -1;

    private final Logger _log;

    public OmnipodPdm(Context context)
    {
        _context = context;
        _log =  LoggerFactory.getLogger(L.PUMP);
    }

    public void OnStart() {
        _lastStatus = OmnipodStatus.fromJson(SP.getString(R.string.key_omnipod_status, null));
    }

    public void OnStop() {
    }

    public boolean IsInitialized() {
        return true;
//        return (_lastStatus != null &&
//                (_lastStatus.state_progress == 8 || _lastStatus.state_progress == 9));
    }

    public boolean IsSuspended() {
        return false;
    }

    public boolean IsBusy() {
        return false;
    }

    public boolean IsConnected() {
        return true;
    }

    public void Connect() {
    }

    public boolean IsConnecting() {
        return false;
    }

    public void StopConnecting() {
    }

    public void FinishHandshaking() { }

    public boolean IsHandshakeInProgress() {
        return false;
    }

    public void Disconnect() {}

    public synchronized void onResultReceived(OmniCoreResult result) {
        if (result != null && !result.canceled && result.status != null) {
            _lastResult = result;
            _lastKnownBatteryLevel = result.battery_level;

            if (_lastStatus != null && (_lastStatus.radio_address != result.status.radio_address ||
                    _lastStatus.id_lot != result.status.id_lot || _lastStatus.id_t != result.status.id_t)
                && _lastStatus.radio_address != 0 && _lastStatus.id_lot != 0 && _lastStatus.id_t != 0)
            {
                MainApp.bus().post(new EventDismissNotification(Notification.OMNIPY_POD_CHANGE));
                Notification notification = new Notification(Notification.OMNIPY_POD_CHANGE,
                        String.format(MainApp.gs(R.string.omnipod_pod_state_POD_IDs_has_been_removed), _lastStatus.id_lot, _lastStatus.id_t), Notification.NORMAL);       //"Pod with Lot %d and Serial %d has been removed."
                _lastStatus = null;
                MainApp.bus().post(new EventNewNotification(notification));
            }

            if (result.status.state_progress == 0 && (_lastStatus == null || _lastStatus.state_progress != 0))
            {
                MainApp.bus().post(new EventDismissNotification(Notification.OMNIPY_POD_STATUS));
                Notification notification = new Notification(Notification.OMNIPY_POD_STATUS, MainApp.gs(R.string.omnipod_pod_state_No_pod_registered), Notification.NORMAL); //"No pod registered"
                MainApp.bus().post(new EventNewNotification(notification));
            }
            else if (result.status.state_faulted && (_lastStatus == null || !_lastStatus.state_faulted))
            {
                // TODO: log fault event
                MainApp.bus().post(new EventDismissNotification(Notification.OMNIPY_POD_STATUS));
                Notification notification = new Notification(Notification.OMNIPY_POD_STATUS,
                        String.format(MainApp.gs(R.string.Pod_faulted_with_error), result.status.fault_event), Notification.URGENT);                //"Pod faulted with error: %d"
                MainApp.bus().post(new EventNewNotification(notification));
            }
            else if (result.status.state_progress < 8 && result.status.state_progress > 0 &&
                    (_lastStatus == null || _lastStatus.state_progress == 0))
            {
                MainApp.bus().post(new EventDismissNotification(Notification.OMNIPY_POD_STATUS));
                Notification notification = new Notification(Notification.OMNIPY_POD_STATUS, MainApp.gs(R.string.omnipod_pod_state_Pod_in_activation_progress), Notification.INFO);      //"Pod in activation progress"
                MainApp.bus().post(new EventNewNotification(notification));
            }
            else if (result.status.state_progress == 8 && (_lastStatus == null || _lastStatus.state_progress < 8))
            {
                // TODO: log pod activated
                MainApp.bus().post(new EventDismissNotification(Notification.OMNIPY_POD_STATUS));
                Notification notification = new Notification(Notification.OMNIPY_POD_STATUS, MainApp.gs(R.string.omnipod_pod_state_Pod_is_activated_and_running), Notification.INFO);      //"Pod is activated and running"
                MainApp.bus().post(new EventNewNotification(notification));
            }
            else if (result.status.state_progress == 9 && (_lastStatus == null || _lastStatus.state_progress == 8))
            {
                // TODO: log reservoir event
                MainApp.bus().post(new EventDismissNotification(Notification.OMNIPY_POD_STATUS));
                Notification notification = new Notification(Notification.OMNIPY_POD_STATUS, MainApp.gs(R.string.omnipod_pod_state_Pod_running_with_low_reservoir_less_than_50U), Notification.NORMAL);    //"Pod running with low reservoir (less than 50U)"
                MainApp.bus().post(new EventNewNotification(notification));
            }
            else if (result.status.state_progress > 9 && (_lastStatus == null || _lastStatus.state_progress <= 9))
            {
                // TODO: log pod stopped
                MainApp.bus().post(new EventDismissNotification(Notification.OMNIPY_POD_STATUS));
                Notification notification = new Notification(Notification.OMNIPY_POD_STATUS, MainApp.gs(R.string.omnipod_pod_state_Pod_stopped), Notification.NORMAL);       //"Pod stopped"
                MainApp.bus().post(new EventNewNotification(notification));
            }

            if ((result.status.state_progress == 8 || result.status.state_progress == 9) && result.status.state_alert != 0
                && (_lastStatus == null || _lastStatus.state_alert != result.status.state_alert))
            {
                String alertText = "";
                for (String alert : getAlerts(result.status.state_alert)) {
                    if (alertText.length() == 0)
                        alertText += alert;
                    else
                        alertText += ", " + alert;
                }
                MainApp.bus().post(new EventDismissNotification(Notification.OMNIPY_POD_STATUS));
                Notification notification = new Notification(Notification.OMNIPY_POD_STATUS, MainApp.gs(R.string.omnipod_pod_state_Pod_alert) + alertText, Notification.NORMAL);       //"Pod alert: "
                MainApp.bus().post(new EventNewNotification(notification));
            }

            _lastStatus = result.status;
            SP.putString(R.string.key_omnipod_status, _lastStatus.asJson());
        }
        MainApp.bus().post(new EventOmnipodUpdateGui());
    }

    private long _lastStatusRequest = 0;
    public void UpdateStatus() {
        if (IsConnected() && IsInitialized() && !IsBusy()) {
            long t0 = System.currentTimeMillis();
            if (t0 - _lastStatusRequest > 60000) {
                _lastStatusRequest = t0;
                onResultReceived(new OmniCoreStatusRequest(0).getResult());
            }
        }
    }

    public long GetLastUpdated() {
        if (_lastStatus != null)
            return  (long)_lastStatus.state_last_updated * 1000;
        else
            return 0;
    }

    public long GetLastResultDate() {
        if (_lastResult != null)
            return  (long)_lastResult.datetime * 1000;
        else
            return System.currentTimeMillis();
    }

    public ArrayList<String> getAlerts(int alertMask)
    {
        ArrayList<String> alerts = new ArrayList<>();
        if ((alertMask & 0x01) > 0)MainApp.bus().post(new EventDismissNotification(Notification.OMNIPY_CONNECTION_STATUS));
            alerts.add(MainApp.gs(R.string.omnipod_pod_alerts_Auto_off));     //"Auto-off"
        if ((alertMask & 0x02) > 0)
            alerts.add(MainApp.gs(R.string.omnipod_pod_alerts_Unknown));      //"Unknown"
        if ((alertMask & 0x04) > 0)
            alerts.add(MainApp.gs(R.string.omnipod_pod_alerts_Pod_expiring_soon));        //"Pod expiring soon"
        if ((alertMask & 0x08) > 0)
            alerts.add(MainApp.gs(R.string.omnipod_pod_alerts_Replace_pod_soon));     //"Replace pod soon"
        if ((alertMask & 0x10) > 0)
            alerts.add(MainApp.gs(R.string.omnipod_pod_alerts_Low_reservoir));        //"Low reservoir"
        if ((alertMask & 0x20) > 0)
            alerts.add(MainApp.gs(R.string.omnipod_pod_alerts_Insulin_Suspended));        //"Insulin Suspended"
        if ((alertMask & 0x40) > 0)
            alerts.add(MainApp.gs(R.string.omnipod_pod_alerts_End_of_insulin_suspend));       //"End of insulin suspend"
        if ((alertMask & 0x80) > 0)
            alerts.add(MainApp.gs(R.string.omnipod_pod_alerts_Pod_expired));      //"Pod expired"
        return alerts;
    }

    public String getPodStatusText()
    {
        if (_lastStatus == null)
            return MainApp.gs(R.string.omnipod_pod_status_Status_unknown);        //"Status unknown"

        StringBuilder sb = new StringBuilder();
        sb.append("Lot: ").append(_lastStatus.id_lot).append(" TID: ").append(_lastStatus.id_t)
                .append(MainApp.gs(R.string.omnipod_pod_status_radio_address)) //" Radio address: "
                .append(String.format("%08X", _lastStatus.radio_address));

        sb.append("\n" + MainApp.gs(R.string.omnipod_pod_status_status));
        if (_lastStatus.state_progress < 8)
        {
            sb.append(MainApp.gs(R.string.omnipod_pod_status_Not_yet_running));    //"Not yet running"
        }
        else if (_lastStatus.state_progress == 8)
        {
            sb.append(MainApp.gs(R.string.omnipod_pod_status_Running));   //"Running"
        }
        else if (_lastStatus.state_progress == 9)
        {
            sb.append(MainApp.gs(R.string.omnipod_pod_status_Running_with_low_insulin_below50U));   //"Running with low insulin (<50U)"
        }
        else
        {
            sb.append(MainApp.gs(R.string.omnipod_pod_status_Inactive));  //"Inactive"
        }

        if (_lastStatus.state_faulted)
        {
            sb.append("\n" + MainApp.gs(R.string.omnipod_pod_status_POD_FAULTED));     //"POD FAULTED"
        }

        int minutes = _lastStatus.state_active_minutes;
        int days = minutes / 60 / 24;
        minutes -= days * 60 * 24;
        int hours = minutes / 60;
        minutes -= hours * 60;

        sb.append("\n" + MainApp.gs(R.string.omnipod_pod_status_Total_insulin_delivered)).append(String.format("%3.2f",      //"Total insulin delivered: "
                _lastStatus.insulin_delivered)).append("u");
        sb.append("\n" + MainApp.gs(R.string.omnipod_pod_status_Reservoir));     //"\nReservoir: "
        if (_lastStatus.insulin_reservoir > 50)
            sb.append(MainApp.gs(R.string.omnipod_pod_status_more_than_50U));      //"more than 50U"
        else
            sb.append(String.format("%2.2f", _lastStatus.insulin_reservoir)).append("u");

        sb.append("\n" + MainApp.gs(R.string.omnipod_pod_status_Pod_age)).append(String.format(MainApp.gs(R.string.pod_age_format), days, hours, minutes));       //"Pod age: "         "%dd%dh%dm"

        Date date = new Date((long)_lastStatus.state_last_updated * 1000);
        DateFormat dateFormat = android.text.format.DateFormat.getTimeFormat(_context);
        sb.append("\n" + MainApp.gs(R.string.omnipod_pod_status_Status_updated)).append(dateFormat.format(date));        //"Status updated: "

        return sb.toString();
    }

    public boolean IsProfileSet(Profile profile) {
        if (IsInitialized() && _lastStatus != null) {
            if (_lastStatus.var_basal_schedule == null || _lastStatus.var_basal_schedule.length == 0)
            {
                return false;
            }
            else
                return verifySchedule(profile);
        }
        return false;
    }

    private boolean verifySchedule(Profile profile)
    {
        TimeZone tz = profile.getTimeZone();
        int offset_minutes = (tz.getRawOffset() + tz.getDSTSavings()) / (60 * 1000);
        if (_lastStatus.var_utc_offset != offset_minutes)
            return false;

        BigDecimal[] scheduleToVerify = getBasalScheduleFromProfile(profile);
        for(int i=0; i<48; i++)
            if (_lastStatus.var_basal_schedule[i].compareTo(scheduleToVerify[i]) != 0)
                return false;
        return true;
    }

    public double GetBaseBasalRate() {
        if (IsInitialized() && _lastStatus != null && _lastStatus.var_basal_schedule != null
                && _lastStatus.var_basal_schedule.length > 0) {
            long t = System.currentTimeMillis();
            t += _lastStatus.var_utc_offset;
            Date dt = new Date(t);
            int h = dt.getHours();
            int m = dt.getMinutes();

            int index = h * 2;
            if (m >= 30)
                index++;

            return _lastStatus.var_basal_schedule[index].doubleValue();
        }
        else
            return 0d;
    }

    public OmniCoreResult SetNewBasalProfile(Profile profile) {
        OmniCoreResult result = null;
        if (IsConnected() && IsInitialized() && _lastStatus != null) {
            TimeZone tz = profile.getTimeZone();
            int offset_minutes = (tz.getRawOffset() + tz.getDSTSavings()) / (60 * 1000);
            BigDecimal[] basalSchedule = getBasalScheduleFromProfile(profile);
            result = new OmniCoreBasalScheduleRequest(basalSchedule, offset_minutes).getResult();
            onResultReceived(result);
        }
        return result;
    }

    public OmniCoreResult Bolus(BigDecimal bolusUnits) {
        OmniCoreResult r = null;
        if (IsConnected() && IsInitialized()) {
            r = new OmniCoreBolusRequest(bolusUnits).getResult();
            this.onResultReceived(r);
        }
        return r;
    }

    public OmniCoreResult CancelBolus() {
        return new OmniCoreCancelBolusRequest().getResult();
    }

    public OmniCoreResult SetTempBasal(BigDecimal iuRate, BigDecimal durationHours) {
        OmniCoreResult r = null;
        if (IsConnected() && IsInitialized()) {
            r = new OmniCoreTempBasalRequest(iuRate, durationHours).getResult();
            this.onResultReceived(r);
        }
        return r;
    }

    public OmniCoreResult CancelTempBasal() {
        OmniCoreResult result = null;
        if (IsConnected() && IsInitialized()) {
            result = new OmniCoreCancelTempBasalRequest().getResult();
            this.onResultReceived(result);
        }
        return result;
    }

    public String GetPodId() {
        return String.format("L%dT%d", _lastStatus.id_lot, _lastStatus.id_t);
    }

    public String GetStatusShort() {
        if (_lastStatus != null) {
            if (_lastStatus.state_faulted)
                return "FAULT";
            if (_lastStatus.state_progress == 9)
                return "IU<50";
            if (_lastStatus.state_progress == 8)
                return "OK";
        }
        return "UNKNOWN";
    }

    public BigDecimal GetExactInsulinUnits(double iu)
    {
        BigDecimal big20 = new BigDecimal("20");
        // round to 0.05's complements
        return new BigDecimal(iu).multiply(big20).setScale(0, RoundingMode.HALF_UP).setScale(2).divide(big20);
    }

    public BigDecimal GetExactHourUnits(int minutes)
    {
        BigDecimal big30 = new BigDecimal("30");
        return new BigDecimal(minutes).divide(big30).setScale(0, RoundingMode.HALF_UP).setScale(1).divide(new BigDecimal(2));
    }

    public BigDecimal[] getBasalScheduleFromProfile(Profile profile)
    {
        BigDecimal[] basalSchedule = new BigDecimal[48];
        int secondsSinceMidnight = 0;
        for(int i=0; i<48; i++)
        {
            basalSchedule[i] = GetExactInsulinUnits(profile.getBasalTimeFromMidnight(secondsSinceMidnight));
            secondsSinceMidnight += 60*30;
        }
        return basalSchedule;
    }

    public double GetReservoirLevel() {
        if (_lastStatus != null)
            return _lastStatus.insulin_reservoir;
        else
            return -1;
    }

    public OmnipodStatus getStatus() {
        return _lastStatus;
    }

    public int getBatteryLevel() {
        return _lastKnownBatteryLevel;
    }
}
